package org.depromeet.team3.batch.restaurant.config

import org.depromeet.team3.batch.restaurant.job.RestaurantDeltaDetectTasklet
import org.depromeet.team3.batch.restaurant.job.RestaurantEnrichmentPublishTasklet
import org.depromeet.team3.batch.restaurant.job.RestaurantFinalizeContextTasklet
import org.depromeet.team3.batch.restaurant.job.RestaurantImportJobStatusListener
import org.depromeet.team3.batch.restaurant.job.RestaurantMasterSyncTasklet
import org.depromeet.team3.batch.restaurant.job.RestaurantRawImportTasklet
import org.depromeet.team3.batch.restaurant.job.RestaurantSnapshotReplaceTasklet
import org.springframework.batch.core.Job
import org.springframework.batch.core.Step
import org.springframework.batch.core.job.builder.JobBuilder
import org.springframework.batch.core.repository.JobRepository
import org.springframework.batch.core.step.builder.StepBuilder
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.transaction.PlatformTransactionManager

@Configuration
@ConditionalOnProperty(prefix = "restaurant.import", name = ["enabled"], havingValue = "true")
class RestaurantImportJobConfig(
    private val jobRepository: JobRepository,
    private val transactionManager: PlatformTransactionManager,
    private val properties: RestaurantImportProperties,
) {

    @Bean
    fun restaurantImportJob(
        rawImportStep: Step,
        deltaDetectStep: Step,
        masterSyncStep: Step,
        snapshotReplaceStep: Step,
        enrichmentPublishStep: Step,
        listener: RestaurantImportJobStatusListener,
    ): Job {
        val builder = JobBuilder("restaurantImportJob", jobRepository)
            .listener(listener)
            .start(rawImportStep)
            .next(deltaDetectStep)
            .next(masterSyncStep)
            .next(snapshotReplaceStep)

        return if (properties.enrichmentPublishEnabled) {
            builder.next(enrichmentPublishStep).build()
        } else {
            builder.build()
        }
    }

    @Bean
    fun restaurantIngestJob(rawImportStep: Step, listener: RestaurantImportJobStatusListener): Job =
        JobBuilder("restaurantIngestJob", jobRepository)
            .listener(listener)
            .start(rawImportStep)
            .build()

    @Bean
    fun restaurantFinalizeJob(
        finalizeContextStep: Step,
        deltaDetectStep: Step,
        masterSyncStep: Step,
        snapshotReplaceStep: Step,
        listener: RestaurantImportJobStatusListener,
    ): Job = JobBuilder("restaurantFinalizeJob", jobRepository)
        .listener(listener)
        .start(finalizeContextStep)
        .next(deltaDetectStep)
        .next(masterSyncStep)
        .next(snapshotReplaceStep)
        .build()

    @Bean
    fun rawImportStep(tasklet: RestaurantRawImportTasklet): Step = taskletStep("restaurantRawImportStep", tasklet)

    @Bean
    fun finalizeContextStep(tasklet: RestaurantFinalizeContextTasklet): Step =
        taskletStep("restaurantFinalizeContextStep", tasklet)

    @Bean
    fun deltaDetectStep(tasklet: RestaurantDeltaDetectTasklet): Step = taskletStep("restaurantDeltaDetectStep", tasklet)

    @Bean
    fun masterSyncStep(tasklet: RestaurantMasterSyncTasklet): Step = taskletStep("restaurantMasterSyncStep", tasklet)

    @Bean
    fun snapshotReplaceStep(tasklet: RestaurantSnapshotReplaceTasklet): Step = taskletStep("restaurantSnapshotReplaceStep", tasklet)

    @Bean
    fun enrichmentPublishStep(tasklet: RestaurantEnrichmentPublishTasklet): Step = taskletStep("restaurantEnrichmentPublishStep", tasklet)

    private fun taskletStep(name: String, tasklet: org.springframework.batch.core.step.tasklet.Tasklet): Step =
        StepBuilder(name, jobRepository)
            .tasklet(tasklet, transactionManager)
            .build()
}
