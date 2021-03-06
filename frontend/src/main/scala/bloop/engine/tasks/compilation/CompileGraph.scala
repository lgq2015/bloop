// scalafmt: { maxcolumn = 130 }
package bloop.engine.tasks.compilation

import java.io.File
import java.nio.file.Path
import java.util.concurrent.{CompletableFuture, ConcurrentHashMap}

import bloop.io.{ParallelOps, AbsolutePath, Paths => BloopPaths}
import bloop.io.ParallelOps.CopyMode
import bloop.data.{Project, ClientInfo}
import bloop.engine.tasks.compilation.CompileExceptions.BlockURI
import bloop.util.Java8Compat.JavaCompletableFutureUtils
import bloop.util.JavaCompat.EnrichOptional
import bloop.engine.{Dag, Leaf, Parent, Aggregate, ExecutionContext}
import bloop.reporter.ReporterAction
import bloop.logging.{Logger, ObservedLogger, LoggerAction, DebugFilter}
import bloop.{Compiler, CompilerOracle, JavaSignal, SimpleIRStore, CompileProducts}
import bloop.engine.caches.LastSuccessfulResult

import monix.eval.Task
import monix.execution.CancelableFuture
import monix.reactive.{Observable, MulticastStrategy}
import xsbti.compile.{EmptyIRStore, IR, IRStore, PreviousResult}

import scala.util.{Failure, Success}

object CompileGraph {
  type CompileTraversal = Task[Dag[PartialCompileResult]]
  private implicit val filter: DebugFilter = DebugFilter.Compilation

  case class BundleInputs(
      project: Project,
      dag: Dag[Project],
      dependentProducts: Map[Project, CompileProducts]
  )

  type IRs = Array[IR]
  case class Inputs(
      bundle: CompileBundle,
      store: IRStore,
      irPromise: CompletableFuture[IRs],
      completeJava: CompletableFuture[Unit],
      transitiveJavaSignal: Task[JavaSignal],
      oracle: CompilerOracle,
      separateJavaAndScala: Boolean,
      dependentResults: Map[File, PreviousResult] = Map.empty
  )

  object Inputs {
    def normal(
        b: CompileBundle,
        s: IRStore,
        p: CompletableFuture[IRs],
        oracle: CompilerOracle,
        separateJavaAndScala: Boolean,
        dependentResults: Map[File, PreviousResult] = Map.empty
    ): Inputs = {
      Inputs(b, s, p, JavaCompleted, JavaContinue, oracle, separateJavaAndScala, dependentResults)
    }
  }

  /**
   * Turns a dag of projects into a task that returns a dag of compilation results
   * that can then be used to debug the evaluation of the compilation within Monix
   * and access the compilation results received from Zinc.
   *
   * @param dag The dag of projects to be compiled.
   * @return A task that returns a dag of compilation results.
   */
  def traverse(
      dag: Dag[Project],
      client: ClientInfo,
      setup: BundleInputs => Task[CompileBundle],
      compile: Inputs => Task[ResultBundle],
      pipeline: Boolean
  ): CompileTraversal = {
    /* We use different traversals for normal and pipeline compilation because the
     * pipeline traversal has an small overhead (2-3%) for some projects. Check
     * https://benchs.scala-lang.org/dashboard/snapshot/sLrZTBfntTxMWiXJPtIa4DIrmT0QebYF */
    if (pipeline) pipelineTraversal(dag, client, setup, compile)
    else normalTraversal(dag, client, setup, compile)
  }

  private final val JavaContinue = Task.now(JavaSignal.ContinueCompilation)
  private final val JavaCompleted = CompletableFuture.completedFuture(())

  private def blockedBy(dag: Dag[PartialCompileResult]): Option[Project] = {
    def blockedFromResults(results: List[PartialCompileResult]): Option[Project] = {
      results match {
        case Nil => None
        case result :: rest =>
          result match {
            case PartialEmpty => None
            case _: PartialSuccess => None
            case f: PartialFailure => Some(f.project)
            case fs: PartialFailures => blockedFromResults(results)
          }
      }
    }

    dag match {
      case Leaf(_: PartialSuccess) => None
      case Leaf(f: PartialFailure) => Some(f.project)
      case Leaf(fs: PartialFailures) => blockedFromResults(fs.failures)
      case Leaf(PartialEmpty) => None
      case Parent(_: PartialSuccess, _) => None
      case Parent(f: PartialFailure, _) => Some(f.project)
      case Parent(fs: PartialFailures, _) => blockedFromResults(fs.failures)
      case Parent(PartialEmpty, _) => None
      case Aggregate(dags) =>
        dags.foldLeft(None: Option[Project]) {
          case (acc, dag) =>
            acc match {
              case Some(_) => acc
              case None => blockedBy(dag)
            }

        }
    }
  }

  case class RunningCompilation(
      traversal: CompileTraversal,
      previousLastSuccessful: LastSuccessfulResult,
      mirror: Observable[Either[ReporterAction, LoggerAction]]
  )

  type RunningCompilationsInAllClients =
    ConcurrentHashMap[CompilerOracle.Inputs, RunningCompilation]
  private val runningCompilations: RunningCompilationsInAllClients =
    new ConcurrentHashMap[CompilerOracle.Inputs, RunningCompilation]()

  private val lastSuccessfulResults =
    new ConcurrentHashMap[Project, LastSuccessfulResult]()

  import monix.execution.atomic.AtomicInt
  private val currentlyUsingDirectories =
    new ConcurrentHashMap[AbsolutePath, AtomicInt]()

  private val emptyOracle = ImmutableCompilerOracle.empty()

  /**
   * Sets up project compilation, deduplicates compilation based on ongoing compilations in all
   * concurrent clients and otherwise runs the compilation of a project.
   *
   * The correctness of the compile deduplication depends on the effects that different clients
   * perceive. For example, it would be incorrect to deduplicate the logic by memoizing the
   * compilation task and not forwarding all the side effects produced during the compilation to
   * all clients. This method takes care of replaying all the events that happen during the
   * compilation of a given project, regardless of the time where clients ask for the same
   * compilation. Most of the magic about how this is setup can be found in [[CompileTask]],
   * where the `setup` function is defined. The compile bundle contains both the observer to append
   * events, that is added to the reporter and logger, as well as the stream to consume the events.
   *
   * @param project The project we want to set up and compile.
   * @param setup The setup function that yields a bundle with unique oracle inputs.
   * @param compile The function that will compile the project.
   * @return A task that may be created by `compile` or may be a reference to a previous task.
   */
  def setupAndDeduplicate(
      client: ClientInfo,
      inputs: BundleInputs,
      setup: BundleInputs => Task[CompileBundle]
  )(
      compile: CompileBundle => CompileTraversal
  ): CompileTraversal = {
    implicit val filter = DebugFilter.Compilation
    setup(inputs).flatMap { bundle =>
      val logger = bundle.logger
      var deduplicate: Boolean = true
      val ongoingCompilation = runningCompilations.computeIfAbsent(
        bundle.oracleInputs,
        (_: CompilerOracle.Inputs) => {
          deduplicate = false
          // Replace client-specific last successful with the most recent result
          val mostRecentSuccessful = {
            val result = lastSuccessfulResults.compute(
              inputs.project,
              (_: Project, current: LastSuccessfulResult) => {
                if (current != null) {
                  // Register that we're using this classes directory in a thread-safe way
                  val counter0 = AtomicInt(1)
                  val counter = currentlyUsingDirectories.putIfAbsent(current.classesDir, counter0)
                  if (counter == null) () else counter.increment(1)
                }
                // We continue with whatever value we receive
                current
              }
            )

            if (result != null && !result.classesDir.exists) {
              LastSuccessfulResult.empty(inputs.project)
            } else if (bundle.latestResult != Compiler.Result.Empty && result != null) {
              result
            } else LastSuccessfulResult.empty(inputs.project)
          }

          val newBundle = bundle.copy(lastSuccessful = mostRecentSuccessful)
          val compileAndUnsubscribe = {
            compile(newBundle)
              .doOnFinish(_ => Task(logger.observer.onComplete()))
              .map { result =>
                // Unregister deduplication atomically and register last successful if any
                processResultAtomically(
                  result,
                  inputs.project,
                  bundle.oracleInputs,
                  mostRecentSuccessful,
                  logger
                )
              }
              .memoize // Without memoization, there is no deduplication
          }

          RunningCompilation(compileAndUnsubscribe, mostRecentSuccessful, bundle.mirror)
        }
      )

      if (!deduplicate) {
        ongoingCompilation.traversal
      } else {
        val rawLogger = logger.underlying
        rawLogger.debug(s"Deduplicating compilation for ${bundle.project.name} (${client}")
        val reporter = bundle.reporter.underlying
        // Don't use `bundle.lastSuccessful`, it's not the final input to `compile`
        val analysis = ongoingCompilation.previousLastSuccessful.previous.analysis().toOption
        val previousSuccessfulProblems =
          Compiler.previousProblemsFromSuccessfulCompilation(analysis)
        val previousProblems =
          Compiler.previousProblemsFromResult(bundle.latestResult, previousSuccessfulProblems)

        // Replay events asynchronously to waiting for the compilation result
        val replayEventsTask = ongoingCompilation.mirror.foreachL {
          case Left(action) =>
            action match {
              case ReporterAction.ReportStartCompilation =>
                reporter.reportStartCompilation(previousProblems)
              case a: ReporterAction.ReportStartIncrementalCycle =>
                reporter.reportStartIncrementalCycle(a.sources, a.outputDirs)
              case a: ReporterAction.ReportProblem => reporter.log(a.problem)
              case ReporterAction.PublishDiagnosticsSummary =>
                reporter.printSummary()
              case a: ReporterAction.ReportNextPhase =>
                reporter.reportNextPhase(a.phase, a.sourceFile)
              case a: ReporterAction.ReportCompilationProgress =>
                reporter.reportCompilationProgress(a.progress, a.total)
              case a: ReporterAction.ReportEndIncrementalCycle =>
                reporter.reportEndIncrementalCycle(a.durationMs, a.result)
              case ReporterAction.ReportCancelledCompilation =>
                reporter.reportCancelledCompilation()
              case a: ReporterAction.ReportEndCompilation =>
                reporter.reportEndCompilation(previousSuccessfulProblems, a.code)
            }
          case Right(action) =>
            action match {
              case LoggerAction.LogErrorMessage(msg) => rawLogger.error(msg)
              case LoggerAction.LogWarnMessage(msg) => rawLogger.warn(msg)
              case LoggerAction.LogInfoMessage(msg) => rawLogger.info(msg)
              case LoggerAction.LogDebugMessage(msg) =>
                rawLogger.debug(msg)
              case LoggerAction.LogTraceMessage(msg) =>
                rawLogger.debug(msg)
            }
        }

        /* The task set up by another process whose memoized result we're going to
         * reuse. To prevent blocking compilations, we execute this task (which will
         * block until its completion is done) in the IO thread pool, which is
         * unbounded. This makes sure that the blocking threads *never* block
         * the computation pool, which could produce a hang in the build server.
         */
        val ongoingCompilationTask =
          ongoingCompilation.traversal.executeOn(ExecutionContext.ioScheduler)

        val deduplicateStreamSideEffectsHandle =
          replayEventsTask.runAsync(ExecutionContext.ioScheduler)

        /**
         * Deduplicate and change the implementation of the task returning the
         * deduplicate compiler result to trigger a syncing process to keep the
         * client external classes directory up-to-date with the new classes
         * directory. This copying process blocks until the background IO work
         * of the deduplicated compilation result has been finished. Note that
         * this mechanism allows pipelined compilations to perform this IO only
         * when the full compilation of a module is finished.
         */
        val obtainResultFromDeduplication = ongoingCompilationTask.map { resultDag =>
          enrichResultDag(resultDag) {
            case s @ PartialSuccess(bundle, _, _, _, compilerResult) =>
              val newCompilerResult = compilerResult.flatMap { results =>
                results.fromCompiler match {
                  case s: Compiler.Result.Success =>
                    // Wait on new classes to be populated for correctness
                    val externalClassesDir = client.getUniqueClassesDirFor(bundle.project)
                    val runningBackgroundTasks = s.backgroundTasks
                      .trigger(externalClassesDir, bundle.tracer)
                      .runAsync(ExecutionContext.ioScheduler)
                    Task.now(results.copy(runningBackgroundTasks = runningBackgroundTasks))
                  case _: Compiler.Result.Cancelled =>
                    // Make sure to cancel the deduplicating task if compilation is cancelled
                    deduplicateStreamSideEffectsHandle.cancel()
                    Task.now(results)
                  case _ => Task.now(results)
                }
              }
              s.copy(result = newCompilerResult)
            case result => result
          }
        }

        val waitUntilDeduplicationFinishes = for {
          result <- obtainResultFromDeduplication
          _ <- Task.fromFuture(deduplicateStreamSideEffectsHandle)
        } yield result

        bundle.tracer.traceTask(s"deduplicating ${bundle.project.name}") { _ =>
          waitUntilDeduplicationFinishes.executeOn(ExecutionContext.ioScheduler)
        }
      }
    }
  }

  /**
   * Runs function [[f]] within the task returning a [[ResultBundle]] for the
   * given compilation. This function is typically used to perform book-keeping
   * related to the compiler deduplication and success of compilations.
   */
  private def enrichResultDag(
      dag: Dag[PartialCompileResult]
  )(f: PartialCompileResult => PartialCompileResult): Dag[PartialCompileResult] = {
    dag match {
      case Leaf(result) => Leaf(f(result))
      case Parent(result, children) => //Parent(f(result), children.map(c => enrichResultDag(c)(f)))
        Parent(f(result), children)
      case Aggregate(_) => sys.error("Unexpected aggregate node in compile result!")
    }
  }

  private def processResultAtomically(
      resultDag: Dag[PartialCompileResult],
      project: Project,
      oinputs: CompilerOracle.Inputs,
      previous: LastSuccessfulResult,
      logger: Logger
  ): Dag[PartialCompileResult] = {

    def unregisterWhenError(): Unit = {
      // If error in result, remove from running compilation and decrement use
      runningCompilations.remove(oinputs)
      val classesDirOfFailedResult = previous.classesDir
      Option(currentlyUsingDirectories.get(classesDirOfFailedResult))
        .foreach(counter => counter.decrement(1))
    }

    // Unregister deduplication atomically and register last successful if any
    enrichResultDag(resultDag) { (p: PartialCompileResult) =>
      p match {
        case s: PartialSuccess =>
          val newResultTask = s.result.flatMap { (results: ResultBundle) =>
            results.successful match {
              case None =>
                unregisterWhenError()
                Task.now(results)
              case Some(successful) =>
                unregisterDeduplicationAndRegisterSuccessful(project, oinputs, successful) match {
                  case None => Task.now(results)
                  case Some(toDeleteResult) =>
                    logger.debug(
                      s"Next request will delete ${toDeleteResult.classesDir}, superseeded by ${successful.classesDir}"
                    )
                    Task {
                      import scala.concurrent.duration.FiniteDuration
                      val populateAndDelete = {
                        // Populate products of previous, it might not have been run
                        toDeleteResult.populatingProducts.materialize
                        // Then populate products from read-only dir of this run
                          .flatMap(_ => successful.populatingProducts.materialize.map(_ => ()))
                          .memoize
                          // Delete in background after running tasks which could be using this dir
                          .doOnFinish { _ =>
                            Task {
                              BloopPaths.delete(toDeleteResult.classesDir)
                            }.executeOn(ExecutionContext.ioScheduler)
                          }
                      }.memoize
                      results.copy(
                        successful = Some(successful.copy(populatingProducts = populateAndDelete))
                      )
                    }
                }
            }
          }

          /**
           * This result task must only be run once and thus needs to be
           * memoized for semantics reasons. The result task can be called
           * several times by the compilation engine driving the execution.
           */
          s.copy(result = newResultTask.memoize)

        case result =>
          unregisterWhenError()
          result
      }
    }
  }

  /**
   * Removes the deduplication and registers the last successful compilation
   * atomically. When registering the last successful compilation, we make sure
   * that the old last successful result is deleted if its count is 0, which
   * means it's not being used by anyone.
   */
  private def unregisterDeduplicationAndRegisterSuccessful(
      project: Project,
      oracleInputs: CompilerOracle.Inputs,
      successful: LastSuccessfulResult
  ): Option[LastSuccessfulResult] = {
    var resultToDelete: Option[LastSuccessfulResult] = None
    runningCompilations.compute(
      oracleInputs,
      (_: CompilerOracle.Inputs, _: RunningCompilation) => {
        lastSuccessfulResults.compute(
          project,
          (_: Project, previous: LastSuccessfulResult) => {
            if (previous != null) {
              val previousClassesDir = previous.classesDir
              val counter = currentlyUsingDirectories.get(previousClassesDir)
              val toDelete = counter == null || {
                // Decrement has to happen within the compute function
                val currentCount = counter.decrementAndGet(1)
                currentCount == 0
              }

              // Register directory to delete if count is 0
              if (toDelete && successful.classesDir != previousClassesDir) {
                resultToDelete = Some(previous)
              }
            }

            // Return successful result we want to replace
            successful
          }
        )
        null
      }
    )

    resultToDelete
  }

  import scala.collection.mutable

  /**
   * Traverses the dag of projects in a normal way.
   *
   * @param dag is the dag of projects.
   * @param computeBundle is the function that sets up the project on every node.
   * @param compile is the task we use to compile on every node.
   * @return A task that returns a dag of compilation results.
   */
  private def normalTraversal(
      dag: Dag[Project],
      client: ClientInfo,
      computeBundle: BundleInputs => Task[CompileBundle],
      compile: Inputs => Task[ResultBundle]
  ): CompileTraversal = {
    val tasks = new mutable.HashMap[Dag[Project], CompileTraversal]()
    def register(k: Dag[Project], v: CompileTraversal): CompileTraversal = { tasks.put(k, v); v }

    /*
     * [[PartialCompileResult]] is our way to represent errors at the build graph
     * so that we can block the compilation of downstream projects. As we have to
     * abide by this contract because it's used by the pipeline traversal too, we
     * turn an actual compiler failure into a partial failure with a dummy
     * `FailPromise` exception that makes the partial result be recognized as error.
     */
    def toPartialFailure(bundle: CompileBundle, res: Compiler.Result): PartialFailure = {
      val results = Task.now(ResultBundle(res, None, CancelableFuture.unit))
      PartialFailure(bundle.project, CompileExceptions.FailPromise, results)
    }

    val es = EmptyIRStore.getStore
    def loop(dag: Dag[Project]): CompileTraversal = {
      tasks.get(dag) match {
        case Some(task) => task
        case None =>
          val task: Task[Dag[PartialCompileResult]] = dag match {
            case Leaf(project) =>
              val bundleInputs = BundleInputs(project, dag, Map.empty)
              setupAndDeduplicate(client, bundleInputs, computeBundle) { bundle =>
                val cf = new CompletableFuture[IRs]()
                compile(Inputs.normal(bundle, es, cf, emptyOracle, false)).map { results =>
                  results.fromCompiler match {
                    case Compiler.Result.Ok(_) =>
                      val resultsNow = Task.now(results)
                      Leaf(PartialSuccess(bundle, es, JavaCompleted, JavaContinue, resultsNow))
                    case res => Leaf(toPartialFailure(bundle, res))
                  }
                }
              }

            case Aggregate(dags) =>
              val downstream = dags.map(loop)
              Task.gatherUnordered(downstream).flatMap { dagResults =>
                Task.now(Parent(PartialEmpty, dagResults))
              }

            case Parent(project, dependencies) =>
              val downstream = dependencies.map(loop)
              Task.gatherUnordered(downstream).flatMap { dagResults =>
                val failed = dagResults.flatMap(dag => blockedBy(dag).toList)
                if (failed.nonEmpty) {
                  // Register the name of the projects we're blocked on (intransitively)
                  val blockedResult = Compiler.Result.Blocked(failed.map(_.name))
                  val blocked = Task.now(ResultBundle(blockedResult, None, CancelableFuture.unit))
                  Task.now(Parent(PartialFailure(project, BlockURI, blocked), dagResults))
                } else {
                  val results: List[PartialSuccess] = {
                    val transitive = dagResults.flatMap(Dag.dfs(_)).distinct
                    transitive.collect { case s: PartialSuccess => s }
                  }

                  val projectResults =
                    results.map(ps => ps.result.map(r => ps.bundle.project -> r))
                  Task.gatherUnordered(projectResults).flatMap { results =>
                    var dependentProducts = new mutable.ListBuffer[(Project, CompileProducts)]()
                    var dependentResults = new mutable.ListBuffer[(File, PreviousResult)]()
                    results.foreach {
                      case (p, ResultBundle(s: Compiler.Result.Success, _, _)) =>
                        val newProducts = s.products
                        dependentProducts.+=(p -> newProducts)
                        val newResult = newProducts.resultForDependentCompilationsInSameRun
                        dependentResults
                          .+=(newProducts.newClassesDir.toFile -> newResult)
                          .+=(newProducts.readOnlyClassesDir.toFile -> newResult)
                      case _ => ()
                    }

                    val cf = new CompletableFuture[IRs]()
                    val resultsMap = dependentResults.toMap
                    val bundleInputs = BundleInputs(project, dag, dependentProducts.toMap)
                    setupAndDeduplicate(client, bundleInputs, computeBundle) { b =>
                      val inputs = Inputs.normal(b, es, cf, emptyOracle, false, resultsMap)
                      compile(inputs).map { results =>
                        results.fromCompiler match {
                          case Compiler.Result.Ok(_) =>
                            val resultsNow = Task.now(results)
                            Parent(
                              PartialSuccess(b, es, JavaCompleted, JavaContinue, resultsNow),
                              dagResults
                            )
                          case res => Parent(toPartialFailure(b, res), dagResults)
                        }
                      }
                    }
                  }
                }
              }
          }
          register(dag, task.memoize)
      }
    }

    loop(dag)
  }

  /**
   * Traverses the dag of projects in such a way that allows compilation pipelining.
   *
   * Note that to use build pipelining, the compilation task needs to have a pipelining
   * implementation where the pickles are generated and the promise in [[Inputs]] completed.
   *
   * @param dag is the dag of projects.
   * @param computeBundle is the function that sets up the project on every node.
   * @param compile is the function that compiles every node, returning a Task.
   * @return A task that returns a dag of compilation results.
   */
  private def pipelineTraversal(
      dag: Dag[Project],
      client: ClientInfo,
      computeBundle: BundleInputs => Task[CompileBundle],
      compile: Inputs => Task[ResultBundle]
  ): CompileTraversal = {
    val tasks = new scala.collection.mutable.HashMap[Dag[Project], CompileTraversal]()
    def register(k: Dag[Project], v: CompileTraversal): CompileTraversal = { tasks.put(k, v); v }

    val es = EmptyIRStore.getStore
    def loop(dag: Dag[Project]): CompileTraversal = {
      tasks.get(dag) match {
        case Some(task) => task
        case None =>
          val task = dag match {
            case Leaf(project) =>
              Task.now(new CompletableFuture[IRs]()).flatMap { cf =>
                val bundleInputs = BundleInputs(project, dag, Map.empty)
                setupAndDeduplicate(client, bundleInputs, computeBundle) { bundle =>
                  val jcf = new CompletableFuture[Unit]()
                  val t = compile(Inputs(bundle, es, cf, jcf, JavaContinue, emptyOracle, true))
                  val running =
                    Task.fromFuture(t.executeWithFork.runAsync(ExecutionContext.scheduler))
                  val completeJavaTask = Task
                    .deferFutureAction(jcf.asScala(_))
                    .materialize
                    .map {
                      case Success(_) => JavaSignal.ContinueCompilation
                      case Failure(_) => JavaSignal.FailFastCompilation(bundle.project.name)
                    }
                    .memoize

                  Task
                    .deferFutureAction(c => cf.asScala(c))
                    .materialize
                    .map { irs =>
                      val store = irs.map(irs => SimpleIRStore(Array(irs)))
                      Leaf(PartialCompileResult(bundle, store, jcf, completeJavaTask, running))
                    }
                }
              }

            case Aggregate(dags) =>
              val downstream = dags.map(loop)
              Task.gatherUnordered(downstream).flatMap { dagResults =>
                Task.now(Parent(PartialEmpty, dagResults))
              }

            case Parent(project, dependencies) =>
              val downstream = dependencies.map(loop)
              Task.gatherUnordered(downstream).flatMap { dagResults =>
                val failed = dagResults.flatMap(dag => blockedBy(dag).toList)
                if (failed.nonEmpty) {
                  // Register the name of the projects we're blocked on (intransitively)
                  val blockedResult = Compiler.Result.Blocked(failed.map(_.name))
                  val blocked = Task.now(ResultBundle(blockedResult, None, CancelableFuture.unit))
                  Task.now(Parent(PartialFailure(project, BlockURI, blocked), dagResults))
                } else {
                  val directResults =
                    Dag.directDependencies(dagResults).collect { case s: PartialSuccess => s }

                  val results: List[PartialSuccess] = {
                    val transitive = dagResults.flatMap(Dag.dfs(_)).distinct
                    transitive.collect { case s: PartialSuccess => s }
                  }

                  // Passing empty map for dependent classes dirs because we load sigs from memory
                  val bundleInputs = BundleInputs(project, dag, Map.empty)
                  setupAndDeduplicate(client, bundleInputs, computeBundle) { bundle =>
                    // Place IRs stores in same order as classes dirs show up in the raw classpath!
                    val classpath = bundle.project.rawClasspath
                    val indexDirs = classpath.iterator.filter(_.isDirectory).zipWithIndex.toMap
                    val dependencyStore = {
                      val transitive = results.flatMap { r =>
                        val classesDir = client.getUniqueClassesDirFor(r.bundle.project)
                        indexDirs.get(classesDir).iterator.map(i => i -> r.store)
                      }
                      SimpleIRStore(
                        transitive.sortBy(_._1).iterator.flatMap(_._2.getDependentsIRs).toArray
                      )
                    }

                    // Signals whether java compilation can proceed or not
                    val sig = aggregateJavaSignals(results.map(_.javaTrigger))
                    val oracle = new ImmutableCompilerOracle(results)
                    Task.now(new CompletableFuture[IRs]()).flatMap { cf =>
                      val jf = new CompletableFuture[Unit]()
                      val t = compile(Inputs(bundle, dependencyStore, cf, jf, sig, oracle, true))
                      val running = t.executeWithFork.runAsync(ExecutionContext.scheduler)
                      val ongoing = Task.fromFuture(running)
                      val completedJava = {
                        Task
                          .deferFutureAction(jf.asScala(_))
                          .materialize
                          .map {
                            case Success(_) => JavaSignal.ContinueCompilation
                            case Failure(_) => JavaSignal.FailFastCompilation(project.name)
                          }
                      }.memoize // Important to memoize this task for performance reasons

                      Task
                        .deferFutureAction(c => cf.asScala(c))
                        .materialize
                        .map { irs =>
                          val store = irs.map { irs =>
                            dependencyStore.merge(SimpleIRStore(Array(irs)))
                          }

                          Parent(
                            PartialCompileResult(bundle, store, jf, completedJava, ongoing),
                            dagResults
                          )
                        }
                    }
                  }
                }
              }
          }

          register(dag, task.memoize)
      }
    }

    loop(dag)
  }

  private def aggregateJavaSignals(xs: List[Task[JavaSignal]]): Task[JavaSignal] = {
    Task
      .gatherUnordered(xs)
      .map { ys =>
        ys.foldLeft(JavaSignal.ContinueCompilation: JavaSignal) {
          case (JavaSignal.ContinueCompilation, JavaSignal.ContinueCompilation) =>
            JavaSignal.ContinueCompilation
          case (f: JavaSignal.FailFastCompilation, JavaSignal.ContinueCompilation) => f
          case (JavaSignal.ContinueCompilation, f: JavaSignal.FailFastCompilation) => f
          case (JavaSignal.FailFastCompilation(ps), JavaSignal.FailFastCompilation(ps2)) =>
            JavaSignal.FailFastCompilation(ps ::: ps2)
        }
      }
  }
}
