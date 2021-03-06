/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.addthis.hydra.job.alert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import com.addthis.basis.util.Parameter;

import com.addthis.hydra.job.IJob;
import com.addthis.hydra.job.alert.types.OnErrorJobAlert;
import com.addthis.hydra.job.alert.types.RekickTimeoutJobAlert;
import com.addthis.hydra.job.alert.types.RuntimeExceededJobAlert;
import com.addthis.maljson.JSONArray;
import com.addthis.maljson.JSONObject;

import com.google.common.collect.ImmutableList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.addthis.hydra.job.alert.AutoGenerated.BASIC_ALERT;
import static com.addthis.hydra.job.alert.AutoGenerated.BASIC_PAGE;

public class JobAlertManagerImpl implements JobAlertManager {

    private static final Logger log = LoggerFactory.getLogger(JobAlertManagerImpl.class);

    private static final long ALERT_REPEAT_MILLIS = Parameter.longValue("spawn.job.alert.repeat", 5 * 60 * 1000);
    private static final long ALERT_DELAY_MILLIS = Parameter.longValue("spawn.job.alert.delay", 60 * 1000);

    private enum AlertAction {CREATE_OR_UPDATE, DELETE, NO_OP}

    private static final long MAX_RUNTIME = Parameter.longValue("alert.auto.runtime.max", 480);
    private static final long RUNTIME_PADDING = Parameter.longValue("alert.auto.runtime.padding", 30);
    private static final long REKICK_PADDING = Parameter.longValue("alert.auto.rekick.padding", 30);
    private static final String EXTRA_DESCRIPTION =
            "\nThis alert was automatically generated by Hydra. Use job settings (Basic Hydra Alerts/Pagerduty) to " +
            "manage. Do not modify directly.";

    private final JobAlertRunner jobAlertRunner;
    private final GroupManager groupManager;

    public JobAlertManagerImpl(
            GroupManager groupManager,
            JobAlertRunner jobAlertRunner,
            ScheduledExecutorService scheduledExecutorService) {
        this.jobAlertRunner = jobAlertRunner;
        this.groupManager = groupManager;
        this.scheduleAlertScan(scheduledExecutorService);
    }

    private void scheduleAlertScan(ScheduledExecutorService scheduledExecutorService) {
        if (scheduledExecutorService != null) {
            scheduledExecutorService.scheduleWithFixedDelay(jobAlertRunner::scanAlerts,
                                                            ALERT_DELAY_MILLIS,
                                                            ALERT_REPEAT_MILLIS,
                                                            TimeUnit.MILLISECONDS);
            log.info("Alert scan scheduled: delay={}s, repeat={}s", ALERT_DELAY_MILLIS / 1000, ALERT_REPEAT_MILLIS / 1000);
        } else {
            log.warn("ScheduledExecutorService is not provided. Alert scan is disabled");
        }
    }

    public void disableAlerts() throws Exception {
        this.jobAlertRunner.disableAlerts();
    }

    public void enableAlerts() throws Exception {
        this.jobAlertRunner.enableAlerts();
    }

    @Override
    public boolean isAlertEnabledAndWorking() {
        return jobAlertRunner.isAlertsEnabled() && !jobAlertRunner.isLastAlertScanFailed();
    }

    public void putAlert(String alertId, AbstractJobAlert alert) {
        jobAlertRunner.putAlert(alertId, alert);
    }

    public void removeAlert(String alertId) {
        jobAlertRunner.removeAlert(alertId);
    }

    public void removeAlertsForJob(String jobId) {
        jobAlertRunner.removeAlertsForJob(jobId);
    }

    public JSONArray fetchAllAlertsArray() {
        return jobAlertRunner.getAlertStateArray();
    }

    public JSONObject fetchAllAlertsMap() {
        return jobAlertRunner.getAlertStateMap();
    }

    public String getAlert(String alertId) {
        return jobAlertRunner.getAlert(alertId);
    }

    /**
     * Create or update auto generated alerts on the job
     *
     * @param job         the job being updated
     * @param basicAlerts true if we want basic alerts
     * @param basicPages  true if we want basic pages
     */
    @Override public void updateBasicAlerts(final IJob job, final boolean basicAlerts, final boolean basicPages) {
        @Nullable final Group group = this.groupManager.getGroup(job.getGroup());
        // don't try to create alerts if config is not set up for the user/group
        if (group == null) {
            log.warn("No group '{}' found for job {}. Unable to create alerts without config.", job.getGroup(), job.getId());
            return;
        }

        final Set<AbstractJobAlert> alerts = this.jobAlertRunner.getAlertsForJob(job.getId());
        BasicAlerts existingBasicAlerts = BasicAlerts.create(alerts, BASIC_ALERT);
        BasicAlerts existingBasicPages = BasicAlerts.create(alerts, BASIC_PAGE);
        this.updateBasicAlert(
                existingBasicAlerts,
                job,
                basicAlerts,
                job.getBasicAlerts(),
                BASIC_ALERT,
                group.email,
                group.webhookURL,
                job::setBasicAlerts
        );
        this.updateBasicAlert(
                existingBasicPages,
                job,
                basicPages,
                job.getBasicPages(),
                BASIC_PAGE,
                group.pagerEmail,
                null,
                job::setBasicPages
        );
    }

    /**
     * Create or update one type of auto generated alerts
     *
     * @param existingAlerts    any alerts that already exist
     * @param job               the job being updated
     * @param alertsShouldExist true if alerts are wanted
     * @param alertsDoExist     true if alerts were wanted before this update
     * @param autoGenerated     alert or page
     * @param email             for the alert
     * @param webhookURL        for the alert
     * @param saveAlertFn       function that updates the alert setting on the job
     */
    @SuppressWarnings("MethodWithTooManyParameters") private void updateBasicAlert(
            BasicAlerts existingAlerts,
            IJob job,
            boolean alertsShouldExist,
            boolean alertsDoExist,
            @Nullable AutoGenerated autoGenerated,
            @Nullable String email,
            @Nullable String webhookURL,
            Consumer<Boolean> saveAlertFn) {
        // only create these alerts if there is a config
        if (email != null) {
            // actually save the job config part
            saveAlertFn.accept(alertsShouldExist);
            AlertAction action = JobAlertManagerImpl.determineAction(alertsShouldExist, alertsDoExist);
            // this will be null if there aren't existing alerts

            if (action == AlertAction.DELETE) {
                existingAlerts.forEach(alert -> this.removeAlert(alert.alertId));
            } else if (action == AlertAction.CREATE_OR_UPDATE) {
                this.createAlerts(existingAlerts, autoGenerated, job, email, webhookURL);
            }
        }
    }

    private static AlertAction determineAction(boolean shouldExist, boolean exists) {
        if (shouldExist) {
            return AlertAction.CREATE_OR_UPDATE;
        } else if (exists) {
            return AlertAction.DELETE;
        }
        return AlertAction.NO_OP;
    }


    private void createAlerts(
            @Nonnull BasicAlerts existingAlerts,
            @Nullable AutoGenerated autoGenerated,
            @Nonnull IJob job,
            @Nullable String email,
            @Nullable String webhookURL) {
        String jobId = job.getId();
        String description = job.getDescription() + EXTRA_DESCRIPTION;
        String errorId = (existingAlerts.getErrorAlert() == null) ? null : existingAlerts.getErrorAlert().alertId;
        String rekickId = (existingAlerts.getRekickAlert() == null) ? null : existingAlerts.getRekickAlert().alertId;
        String runtimeId = (existingAlerts.getRuntimeAlert() == null) ? null : existingAlerts.getRuntimeAlert().alertId;
        OnErrorJobAlert error = new OnErrorJobAlert(errorId,
                description,
                0,
                email,
                webhookURL,
                ImmutableList.of(jobId),
                SuppressChanges.FALSE,
                autoGenerated,
                0,
                null,
                null
        );
        this.putAlert(error.alertId, error);

        Long rekick = job.getRekickTimeout();
        if ((rekick != null) && (rekick > 0)) {
            long rekickTimeout = rekick + REKICK_PADDING;
            RekickTimeoutJobAlert rekickAlert = new RekickTimeoutJobAlert(rekickId,
                    description,
                    rekickTimeout,
                    0,
                    email,
                    webhookURL,
                    ImmutableList.of(jobId),
                    SuppressChanges.FALSE,
                    autoGenerated,
                    0,
                    null,
                    null
            );
            this.putAlert(rekickAlert.alertId, rekickAlert);
        } else if (rekickId != null) {
            this.removeAlert(rekickId);
        }
        Long runtime = job.getMaxRunTime();
        if ((runtime != null) && (runtime > 0)) {
            long runtimeTimeout = JobAlertManagerImpl.calculateRuntimeTimeout(job.getTaskCount(),
                    runtime,
                    job.getMaxSimulRunning()
            );
            RuntimeExceededJobAlert runtimeAlert = new RuntimeExceededJobAlert(runtimeId,
                    description,
                    runtimeTimeout,
                    0,
                    email,
                    webhookURL,
                    ImmutableList.of(jobId),
                    SuppressChanges.FALSE,
                    autoGenerated,
                    0,
                    null,
                    null
            );
            this.putAlert(runtimeAlert.alertId, runtimeAlert);
        } else if (runtimeId != null) {
            this.removeAlert(runtimeId);
        }
    }

    /**
     * Calculates the maximum time a job probably should run, then fudges it a bit.
     */
    private static long calculateRuntimeTimeout(int tasks, long maxRuntime, int maxSimul) {
        double taskMultiplier = 1;
        if (maxSimul > 0) {
            taskMultiplier = Math.ceil((double) tasks / (double) maxSimul);
        }
        long timeout = (long) (((double) maxRuntime * taskMultiplier) + ((double) RUNTIME_PADDING * taskMultiplier));
        if (timeout > MAX_RUNTIME) {
            return MAX_RUNTIME;
        }
        return timeout;
    }
}
