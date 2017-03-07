// See LICENSE for license details.

package firrtl

import logger.LazyLogging
import java.io.Writer
import annotations._

import firrtl.ir.Circuit
import passes.Pass
import Utils.throwInternalError

/**
 * RenameMap maps old names to modified names.  Generated by transformations
 * that modify names
 */
case class RenameMap(map: Map[Named, Seq[Named]] = Map[Named, Seq[Named]]())

/**
 * Container of all annotations for a Firrtl compiler.
 */
case class AnnotationMap(annotations: Seq[Annotation]) {
  def get(id: Class[_]): Seq[Annotation] = annotations.filter(a => a.transform == id)
  def get(named: Named): Seq[Annotation] = annotations.filter(n => n == named)
}

/** Current State of the Circuit
  *
  * @constructor Creates a CircuitState object
  * @param circuit The current state of the Firrtl AST
  * @param form The current form of the circuit
  * @param annotations The current collection of [[firrtl.annotations.Annotation Annotation]]
  * @param renames A map of [[firrtl.annotations.Named Named]] things that have been renamed.
  *   Generally only a return value from [[Transform]]s
  */
case class CircuitState(
    circuit: Circuit,
    form: CircuitForm,
    annotations: Option[AnnotationMap] = None,
    renames: Option[RenameMap] = None) {

  /** Helper for getting just an emitted circuit */
  def emittedCircuitOption: Option[EmittedCircuit] =
    emittedComponents collectFirst { case x: EmittedCircuit => x }
  /** Helper for getting an [[EmittedCircuit]] when it is known to exist */
  def getEmittedCircuit: EmittedCircuit = emittedCircuitOption match {
    case Some(emittedCircuit) => emittedCircuit
    case None => throw new FIRRTLException("No EmittedCircuit found! Check Emitter Annotations")
  }
  /** Helper function for extracting emitted components from annotations */
  def emittedComponents: Seq[EmittedComponent] = {
    val emittedOpt = annotations map (_.annotations collect {
      case EmittedCircuitAnnotation(x) => x
      case EmittedModuleAnnotation(x) => x
    })
    emittedOpt.getOrElse(Seq.empty)
  }
}

/** Current form of the Firrtl Circuit
  *
  * Form is a measure of addition restrictions on the legality of a Firrtl
  * circuit.  There is a notion of "highness" and "lowness" implemented in the
  * compiler by extending scala.math.Ordered. "Lower" forms add additional
  * restrictions compared to "higher" forms. This means that "higher" forms are
  * strictly supersets of the "lower" forms. Thus, that any transform that
  * operates on [[HighForm]] can also operate on [[MidForm]] or [[LowForm]]
  */
sealed abstract class CircuitForm(private val value: Int) extends Ordered[CircuitForm] {
  // Note that value is used only to allow comparisons
  def compare(that: CircuitForm): Int = this.value - that.value
}
/** Chirrtl Form
  *
  * The form of the circuit emitted by Chisel. Not a true Firrtl form.
  * Includes cmem, smem, and mport IR nodes which enable declaring memories
  * separately form their ports. A "Higher" form than [[HighForm]]
  *
  * See [[CDefMemory]] and [[CDefMPort]]
  */
final case object ChirrtlForm extends CircuitForm(3)
/** High Form
  *
  * As detailed in the Firrtl specification
  * [[https://github.com/ucb-bar/firrtl/blob/master/spec/spec.pdf]]
  *
  * Also see [[firrtl.ir]]
  */
final case object HighForm extends CircuitForm(2)
/** Middle Form
  *
  * A "lower" form than [[HighForm]] with the following restrictions:
  *  - All widths must be explicit
  *  - All whens must be removed
  *  - There can only be a single connection to any element
  */
final case object MidForm extends CircuitForm(1)
/** Low Form
  *
  * The "lowest" form. In addition to the restrictions in [[MidForm]]:
  *  - All aggregate types (vector/bundle) must have been removed
  *  - All implicit truncations must be made explicit
  */
final case object LowForm extends CircuitForm(0)
/** Unknown Form
  * 
  * TODO(azidar): Explanation
  */
final case object UnknownForm extends CircuitForm(-1) {
  override def compare(that: CircuitForm): Int = { error("Illegal to compare UnknownForm"); 0 }
}

/** The basic unit of operating on a Firrtl AST */
abstract class Transform extends LazyLogging {
  /** A convenience function useful for debugging and error messages */
  def name: String = this.getClass.getSimpleName
  /** The [[CircuitForm]] that this transform requires to operate on */
  def inputForm: CircuitForm
  /** The [[CircuitForm]] that this transform outputs */
  def outputForm: CircuitForm
  /** Perform the transform
    *
    * @param state Input Firrtl AST
    * @return A transformed Firrtl AST
    */
  protected def execute(state: CircuitState): CircuitState
  /** Convenience method to get annotations relevant to this Transform
    *
    * @param state The [[CircuitState]] form which to extract annotations
    * @return A collection of annotations
    */
  final def getMyAnnotations(state: CircuitState): Seq[Annotation] = state.annotations match {
    case Some(annotations) => annotations.get(this.getClass) // ++ annotations.get(classOf[Transform])
    case None => Nil
  }

  final def runTransform(state: CircuitState): CircuitState = {
    logger.info(s"======== Starting Transform $name ========")

    //val (timeMillis, result) = Utils.time { xform.execute(state) }
    val (timeMillis, result) = Utils.time { execute(state) }

    logger.info(s"""----------------------------${"-" * name.size}---------\n""")
    logger.info(f"Time: $timeMillis%.1f ms")

    val remappedAnnotations = propagateAnnotations(state.annotations, result.annotations, result.renames)

    logger.info(s"Form: ${result.form}")
    logger.debug(s"Annotations:")
    remappedAnnotations.foreach { a =>
      logger.debug(a.serialize)
    }
    logger.debug(s"Circuit:\n${result.circuit.serialize}")
    logger.info(s"======== Finished Transform $name ========\n")

    CircuitState(result.circuit, result.form, Some(AnnotationMap(remappedAnnotations)), None)
  }

  final private def propagateAnnotations(inAnno: Option[AnnotationMap], resAnno: Option[AnnotationMap],
      renameOpt: Option[RenameMap]): Seq[Annotation] = {
    val newAnnotations = {
      val inSet = inAnno.getOrElse(AnnotationMap(Seq.empty)).annotations.toSet
      val resSet = resAnno.getOrElse(AnnotationMap(Seq.empty)).annotations.toSet
      val deleted = (inSet -- resSet).map {
        case DeletedAnnotation(xFormName, delAnno) => DeletedAnnotation(s"$xFormName+$name", delAnno)
        case anno => DeletedAnnotation(name, anno)
      }
      val created = resSet -- inSet
      val unchanged = resSet & inSet
      (deleted ++ created ++ unchanged)
    }

    // For each annotation, rename all annotations.
    val renames = renameOpt.getOrElse(RenameMap()).map
    for {
      anno <- newAnnotations.toSeq
      newAnno <- anno.update(renames.getOrElse(anno.target, Seq(anno.target)))
    } yield newAnno
  }
}

/** For transformations that are simply a sequence of transforms */
abstract class SeqTransform extends Transform {
  def transforms: Seq[Transform]
  def execute(state: CircuitState): CircuitState = {
    require(state.form <= inputForm,
      s"[$name]: Input form must be lower or equal to $inputForm. Got ${state.form}")
    val ret = transforms.foldLeft(state) { (in, xform) => xform.runTransform(in) }
    CircuitState(ret.circuit, outputForm, ret.annotations, ret.renames)
  }
}

/** Defines old API for Emission. Deprecated */
trait Emitter extends Transform {
  @deprecated("Use emission annotations instead", "firrtl 1.0")
  def emit(state: CircuitState, writer: Writer): Unit
}

object CompilerUtils extends LazyLogging {
  /** Generates a sequence of [[Transform]]s to lower a Firrtl circuit
    *
    * @param inputForm [[CircuitForm]] to lower from
    * @param outputForm [[CircuitForm to lower to
    * @return Sequence of transforms that will lower if outputForm is lower than inputForm
    */
  def getLoweringTransforms(inputForm: CircuitForm, outputForm: CircuitForm): Seq[Transform] = {
    // If outputForm is equal-to or higher than inputForm, nothing to lower
    if (outputForm >= inputForm) {
      Seq.empty
    } else {
      inputForm match {
        case ChirrtlForm =>
          Seq(new ChirrtlToHighFirrtl) ++ getLoweringTransforms(HighForm, outputForm)
        case HighForm =>
          Seq(new IRToWorkingIR, new ResolveAndCheck, new transforms.DedupModules,
              new HighFirrtlToMiddleFirrtl) ++ getLoweringTransforms(MidForm, outputForm)
        case MidForm => Seq(new MiddleFirrtlToLowFirrtl) ++ getLoweringTransforms(LowForm, outputForm)
        case LowForm => error("Internal Error! This shouldn't be possible") // should be caught by if above
        case UnknownForm => error("Internal Error! This shouldn't be possible") // should be caught by if above
      }
    }
  }

  /** Merge a Seq of lowering transforms with custom transforms
    *
    * Custom Transforms are inserted based on their [[Transform.inputForm]] and
    * [[Transform.outputForm]]. Custom transforms are inserted in order at the
    * last location in the Seq of transforms where previous.outputForm ==
    * customTransform.inputForm. If a customTransform outputs a higher form
    * than input, [[getLoweringTransforms]] is used to relower the circuit.
    *
    * @example
    *   {{{
    *     // Let Transforms be represented by CircuitForm => CircuitForm
    *     val A = HighForm => MidForm
    *     val B = MidForm => LowForm
    *     val lowering = List(A, B) // Assume these transforms are used by getLoweringTransforms
    *     // Some custom transforms
    *     val C = LowForm => LowForm
    *     val D = MidForm => MidForm
    *     val E = LowForm => HighForm
    *     // All of the following comparisons are true
    *     mergeTransforms(lowering, List(C)) == List(A, B, C)
    *     mergeTransforms(lowering, List(D)) == List(A, D, B)
    *     mergeTransforms(lowering, List(E)) == List(A, B, E, A, B)
    *     mergeTransforms(lowering, List(C, E)) == List(A, B, C, E, A, B)
    *     mergeTransforms(lowering, List(E, C)) == List(A, B, E, A, B, C)
    *     // Notice that in the following, custom transform order is NOT preserved (see note)
    *     mergeTransforms(lowering, List(C, D)) == List(A, D, B, C)
    *   }}}
    *
    * @note Order will be preserved for custom transforms so long as the
    * inputForm of a latter transforms is equal to or lower than the outputForm
    * of the previous transform.
    */
  def mergeTransforms(lowering: Seq[Transform], custom: Seq[Transform]): Seq[Transform] = {
    custom.foldLeft(lowering) { case (transforms, xform) =>
      val index = transforms lastIndexWhere (_.outputForm == xform.inputForm)
      assert(index >= 0 || xform.inputForm == ChirrtlForm, // If ChirrtlForm just put at front
        s"No transform in $lowering has outputForm ${xform.inputForm} as required by $xform")
      val (front, back) = transforms.splitAt(index + 1) // +1 because we want to be AFTER index
      front ++ List(xform) ++ getLoweringTransforms(xform.outputForm, xform.inputForm) ++ back
    }
  }

}

trait Compiler extends LazyLogging {
  def emitter: Emitter

  /** The sequence of transforms this compiler will execute
    * @note The inputForm of a given transform must be higher than or equal to the ouputForm of the
    *       preceding transform. See [[CircuitForm]]
    */
  def transforms: Seq[Transform]

  // Similar to (input|output)Form on [[Transform]] but derived from this Compiler's transforms
  def inputForm = transforms.head.inputForm
  def outputForm = transforms.last.outputForm

  private def transformsLegal(xforms: Seq[Transform]): Boolean =
    if (xforms.size < 2) {
      true
    } else {
      xforms.sliding(2, 1)
            .map { case Seq(p, n) => n.inputForm >= p.outputForm }
            .reduce(_ && _)
    }

  assert(transformsLegal(transforms),
    "Illegal Compiler, each transform must be able to accept the output of the previous transform!")

  /** Perform compilation
    *
    * @param state The Firrtl AST to compile
    * @param writer The java.io.Writer where the output of compilation will be emitted
    * @param customTransforms Any custom [[Transform]]s that will be inserted
    *   into the compilation process by [[CompilerUtils.mergeTransforms]]
    */
  @deprecated("Please use compileAndEmit or other compile method instead", "firrtl 1.0")
  def compile(state: CircuitState,
              writer: Writer,
              customTransforms: Seq[Transform] = Seq.empty): CircuitState = {
    val finalState = compileAndEmit(state, customTransforms)
    finalState.emittedCircuitOption match {
      case Some(emitted) => writer.write(emitted.value)
      case _ => throwInternalError
    }
    finalState
  }

  /** Perform compilation and emit the whole Circuit
    *
    * This is intended as a convenience method wrapping up Annotation creation for the common case.
    * It creates a [[EmitCircuitAnnotation]] that will be consumed by this Transform's emitter. The
    * [[EmittedCircuit]] can be extracted from the returned [[CircuitState]] via
    * [[CircuitState.emittedCircuitOption]]
    *
    * @param state The Firrtl AST to compile
    * @param customTransforms Any custom [[Transform]]s that will be inserted
    *   into the compilation process by [[CompilerUtils.mergeTransforms]]
    * @return result of compilation with emitted circuit annotated
    */
  def compileAndEmit(state: CircuitState,
                     customTransforms: Seq[Transform] = Seq.empty): CircuitState = {
    val emitAnno = EmitCircuitAnnotation(emitter.getClass)
    // TODO This is ridiculous. Fix Annotations
    val annotations = state.annotations.map(_.annotations).getOrElse(Seq.empty)
    val annotationMap = AnnotationMap(annotations :+ emitAnno)

    // Run compiler
    compile(state.copy(annotations = Some(annotationMap)), customTransforms)
  }

  /** Perform compilation
    *
    * Emission will only be performed if [[EmitAnnotation]]s are present
    *
    * @param state The Firrtl AST to compile
    * @param customTransforms Any custom [[Transform]]s that will be inserted into the compilation
    *   process by [[CompilerUtils.mergeTransforms]]
    * @return result of compilation
    */
  def compile(state: CircuitState, customTransforms: Seq[Transform]): CircuitState = {
    val allTransforms = CompilerUtils.mergeTransforms(transforms, customTransforms) :+ emitter

    val finalState = allTransforms.foldLeft(state) { (in, xform) => xform.runTransform(in) }
    finalState
  }

}

