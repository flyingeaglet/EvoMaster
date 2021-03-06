package org.evomaster.core.search.service.mutator

import com.google.inject.Inject
import org.evomaster.core.EMConfig
import org.evomaster.core.Lazy
import org.evomaster.core.database.DbAction
import org.evomaster.core.database.DbActionUtils
import org.evomaster.core.search.EvaluatedIndividual
import org.evomaster.core.search.Individual
import org.evomaster.core.search.gene.Gene
import org.evomaster.core.search.service.*
import org.evomaster.core.search.service.mutator.geneMutation.ArchiveMutator
import org.evomaster.core.search.tracer.ArchiveMutationTrackService
import org.evomaster.core.search.tracer.TraceableElementCopyFilter
import org.evomaster.core.search.tracer.TrackOperator

abstract class Mutator<T> : TrackOperator where T : Individual {

    @Inject
    protected lateinit var randomness: Randomness

    @Inject
    protected lateinit var ff: FitnessFunction<T>

    @Inject
    protected lateinit var time: SearchTimeController

    @Inject
    protected lateinit var apc: AdaptiveParameterControl

    @Inject
    protected lateinit var structureMutator: StructureMutator

    @Inject
    protected lateinit var config: EMConfig

    @Inject
    private lateinit var tracker : ArchiveMutationTrackService

    @Inject
    protected lateinit var archiveMutator : ArchiveMutator

    @Inject
    protected lateinit var mwc : MutationWeightControl

    /**
     * @param mutatedGenes is used to record what genes are mutated within [mutate], which can be further used to analyze impacts of genes.
     * @return a mutated copy
     */
    abstract fun mutate(individual: EvaluatedIndividual<T>, targets: Set<Int> = setOf(), mutatedGenes: MutatedGeneSpecification? = null): T

    /**
     * @param individual an individual to mutate
     * @param evi a reference of the individual to mutate
     * @return a list of genes that are allowed to mutate
     */
    abstract fun genesToMutation(individual : T, evi: EvaluatedIndividual<T>) : List<Gene>

    /**
     * @param individual an individual to mutate
     * @param evi a reference of the individual to mutate
     * @param targets to cover with this mutation
     * @return a list of genes that are selected to mutate
     */
    abstract fun selectGenesToMutate(individual: T, evi: EvaluatedIndividual<T>, targets: Set<Int> = setOf(), mutatedGenes: MutatedGeneSpecification?) : List<Gene>

    /**
     * @return whether do a structure mutation
     */
    abstract fun doesStructureMutation(individual : T) : Boolean

    open fun postActionAfterMutation(individual: T){}

    open fun update(previous: EvaluatedIndividual<T>, mutated: EvaluatedIndividual<T>, mutatedGenes: MutatedGeneSpecification?, mutationEvaluated: EvaluatedMutation){}

    /**
     * @param upToNTimes how many mutations will be applied. can be less if running out of time
     * @param individual which will be mutated
     * @param archive where to save newly mutated individuals (if needed, eg covering new targets)
     */
    fun mutateAndSave(upToNTimes: Int, individual: EvaluatedIndividual<T>, archive: Archive<T>)
            : EvaluatedIndividual<T> {

        var current = individual

        // tracking might be null if the current is never mutated
        preHandlingTrackedIndividual(current)

        val targets = archive.notCoveredTargets().toMutableSet()

        for (i in 0 until upToNTimes) {

            val currentWithTraces = current.copy(tracker.getCopyFilterForEvalInd(current))

            if (!time.shouldContinueSearch()) {
                break
            }
            val mutatedGenes = MutatedGeneSpecification()

            structureMutator.addInitializingActions(current, mutatedGenes)

            if(mutatedGenes.addedInitializationGenes.isNotEmpty() && archiveMutator.enableArchiveSelection()){
                current.updateDbActionGenes(current.individual, mutatedGenes.addedInitializationGenes)
            }

            Lazy.assert{DbActionUtils.verifyActions(current.individual.seeInitializingActions().filterIsInstance<DbAction>())}

            val mutatedInd = mutate(current, targets, mutatedGenes)
            mutatedGenes.setMutatedIndividual(mutatedInd)

            Lazy.assert{DbActionUtils.verifyActions(mutatedInd.seeInitializingActions().filterIsInstance<DbAction>())}

            //Shall we prioritize the targets based on mutation sampling strategy eg, feedbackDirectedSampling?
            val mutated = ff.calculateCoverage(mutatedInd, setOf())
                    ?: continue

            //evaluated mutated by comparing with current using employed targets
            val result = evaluateMutation(mutated, current, targets, archive)

            //enable further actions for extracting
            update(currentWithTraces, mutated, mutatedGenes, result)

            archiveMutator.saveMutatedGene(mutatedGenes, index = time.evaluatedIndividuals, individual = mutatedInd, evaluatedMutation = result, targets = targets)

            val mutatedWithTraces = when{
                config.enableTrackEvaluatedIndividual-> currentWithTraces.next(
                        next = mutated, copyFilter = TraceableElementCopyFilter.WITH_ONLY_EVALUATED_RESULT, evaluatedResult = result)!!
                config.enableTrackIndividual -> {
                    currentWithTraces.nextForIndividual(next = mutated,  evaluatedResult = result)!!
                }
                else -> mutated
            }

            //TODO refactor when archive-mutation branch is merged
            //update impacts
            if (archiveMutator.doCollectImpact()){
                mutatedWithTraces.updateImpactOfGenes(mutatedGenes, targets, config.secondaryObjectiveStrategy, config.bloatControlForSecondaryObjective)
            }

            current = saveMutation(result, archive, currentWithTraces, mutatedWithTraces)

            // gene mutation evaluation
            if (archiveMutator.enableArchiveGeneMutation()){
                //TODO feedback archive-based gene mutation
            }

            when(config.mutationTargetsSelectionStrategy){
                EMConfig.MutationTargetsSelectionStrategy.FIRST_NOT_COVERED_TARGET ->{}
                EMConfig.MutationTargetsSelectionStrategy.EXPANDED_UPDATED_NOT_COVERED_TARGET ->{
                    targets.addAll(archive.notCoveredTargets())
                }
                EMConfig.MutationTargetsSelectionStrategy.UPDATED_NOT_COVERED_TARGET ->{
                    targets.clear()
                    targets.addAll(archive.notCoveredTargets())
                }
            }
        }
        return current
    }

    fun mutateAndSave(individual: EvaluatedIndividual<T>, archive: Archive<T>)
            : EvaluatedIndividual<T>? {

        structureMutator.addInitializingActions(individual,null)

        return ff.calculateCoverage(mutate(individual), setOf())
                ?.also { archive.addIfNeeded(it) }
    }

    /**
     * @return a result by comparing mutated individual [mutated] with before [current] regarding [targets].
     */
    fun evaluateMutation(mutated: EvaluatedIndividual<T>, current: EvaluatedIndividual<T>, targets: Set<Int>, archive: Archive<T>): EvaluatedMutation {
        // global check
        if (archive.wouldReachNewTarget(mutated)) return EvaluatedMutation.BETTER_THAN

        /*
            to compare mutated with current,
            targets for this comparision, employ targets to evaluate individual (i.e., targets in their fitness) can lead to different results.

            e.g., A1 is mutated to A2 by manipulating gene [a], and gene [a] affects target Ta
            1) fitness of A1 includes heuristic for Tb, Tc, fitness of A2 includes heuristic for Ta
         */
        return compare(mutated, current, targets)
    }

    private fun compare(mutated: EvaluatedIndividual<T>, current: EvaluatedIndividual<T>, targets: Set<Int>): EvaluatedMutation {
        // current is better than mutated
        val beforeBetter = current.fitness.subsumes(other = mutated.fitness, targetSubset = targets, config = config)
        if (beforeBetter) return EvaluatedMutation.WORSE_THAN
        if (mutated.fitness.subsumes(current.fitness, targets, config)) return EvaluatedMutation.BETTER_THAN
        return EvaluatedMutation.EQUAL_WITH
    }

    private fun compareReachedTargets(mutated: EvaluatedIndividual<T>, current: EvaluatedIndividual<T>): EvaluatedMutation {
        if (current.fitness.reachMoreTargets(mutated.fitness)) return EvaluatedMutation.WORSE_THAN
        if (mutated.fitness.reachMoreTargets(current.fitness)) return EvaluatedMutation.BETTER_THAN
        return EvaluatedMutation.EQUAL_WITH
    }

    private fun preHandlingTrackedIndividual(current: EvaluatedIndividual<T>){
        if (config.trackingEnabled()){
            if (config.enableTrackEvaluatedIndividual && current.tracking == null){
                current.wrapWithTracking(null, config.maxLengthOfTraces, mutableListOf())
                current.pushLatest(current.copy(TraceableElementCopyFilter.WITH_ONLY_EVALUATED_RESULT))
            }
            if (config.enableTrackIndividual && current.individual.tracking == null){
                current.individual.wrapWithTracking(null, config.maxLengthOfTraces, mutableListOf())
                current.individual.pushLatest(current.copy(TraceableElementCopyFilter.WITH_ONLY_EVALUATED_RESULT))
            }
        }
    }


    fun saveMutation(evaluatedMutation: EvaluatedMutation, archive: Archive<T>, current: EvaluatedIndividual<T>, mutated: EvaluatedIndividual<T>) : EvaluatedIndividual<T>{
        // if mutated is not worse than current, we employ the mutated for next mutation
        if (evaluatedMutation.isEffective()){
            /*
                worse mutated might be added into archive if there exist space in population.
                in this case, we only attempt to add individual into archive when it is not worse than current
             */
            archive.addIfNeeded(mutated)
            return mutated
        }
        return current
    }
}