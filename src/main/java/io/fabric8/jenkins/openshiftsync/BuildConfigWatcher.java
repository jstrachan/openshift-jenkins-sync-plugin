/**
 * Copyright (C) 2016 Red Hat, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.fabric8.jenkins.openshiftsync;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.model.Job;
import hudson.model.TopLevelItem;
import hudson.util.XStream2;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.openshift.api.model.BuildConfig;
import io.fabric8.openshift.api.model.BuildConfigList;
import jenkins.model.Jenkins;
import org.apache.tools.ant.filters.StringInputStream;
import org.jvnet.hudson.reactor.ReactorException;

import javax.xml.transform.Source;
import javax.xml.transform.stream.StreamSource;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

import static io.fabric8.jenkins.openshiftsync.BuildConfigToJobMapper.mapBuildConfigToJob;
import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.isJenkinsBuildConfig;

/**
 * Watches {@link BuildConfig} objects in OpenShift and for WorkflowJobs we ensure there is a
 * suitable Jenkins Job object defined with the correct configuration
 */
public class BuildConfigWatcher implements Watcher<BuildConfig> {
  private static final AtomicBoolean openshiftUpdatingJob = new AtomicBoolean(false);
  private static final Map<NamespaceName, Long> buildConfigVersions = new HashMap<>();

  private final Logger logger = Logger.getLogger(getClass().getName());
  private final String defaultNamespace;

  /**
   * Determines if an update to a Job comes from OpenShift via a {@link BuildConfig} change or
   * via Jenkins itself (REST API, web console etc)
   *
   * @return true if OpenShift is updating the job or false if not
   */
  public static boolean isOpenShiftUpdatingJob() {
    return openshiftUpdatingJob.get();
  }

  public BuildConfigWatcher(String defaultNamespace) {
    this.defaultNamespace = defaultNamespace;
  }


  @Override
  public void onClose(KubernetesClientException e) {
    if (e != null) {
      logger.warning(e.toString());
    }
  }

  @SuppressFBWarnings("NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE")
  public void onInitialBuildConfigs(BuildConfigList buildConfigs) {
    List<BuildConfig> items = buildConfigs.getItems();
    if (items != null) {
      for (BuildConfig buildConfig : items) {
        try {
          upsertJob(buildConfig);
        } catch (IOException e) {
          e.printStackTrace();
        }
      }
    }
  }

  @SuppressFBWarnings("SF_SWITCH_NO_DEFAULT")
  @Override
  public void eventReceived(Watcher.Action action, BuildConfig buildConfig) {
    try {
      switch (action) {
        case ADDED:
          upsertJob(buildConfig);
          break;
        case DELETED:
          deleteJob(buildConfig);
          break;
        case MODIFIED:
          modifyJob(buildConfig);
          break;
      }
    } catch (IOException | InterruptedException e) {
      logger.log(Level.WARNING, "Caught: " + e, e);
    }
  }

  @SuppressFBWarnings("NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE")
  private void upsertJob(BuildConfig buildConfig) throws IOException {
    if (isJenkinsBuildConfig(buildConfig)) {
      synchronized (buildConfigVersions) {
        openshiftUpdatingJob.set(true);
        try {
          NamespaceName namespacedName = NamespaceName.create(buildConfig);
          Long resourceVersion = getResourceVersion(buildConfig);
          Long previousResourceVersion = buildConfigVersions.get(namespacedName);

          // lets only process this BuildConfig if the resourceVersion is newer than the last one we processed
          if (previousResourceVersion == null || (resourceVersion != null && resourceVersion > previousResourceVersion)) {
            buildConfigVersions.put(namespacedName, resourceVersion);

            String jobName = OpenShiftUtils.jenkinsJobName(buildConfig, defaultNamespace);
            Job jobFromBuildConfig = mapBuildConfigToJob(buildConfig, defaultNamespace);
            if (jobFromBuildConfig == null) {
              return;
            }

            InputStream jobStream = new StringInputStream(new XStream2().toXML(jobFromBuildConfig));

            Jenkins jenkins = Jenkins.getInstance();
            if (jenkins == null) {
              logger.warning("No jenkins instance so cannot upsert job " + jobName + " from BuildConfig " + namespacedName + " with revision: " + resourceVersion);
            } else {
              Job job = jenkins.getItem(jobName, jenkins, Job.class);
              if (job == null) {
                jenkins.createProjectFromXML(
                  jobName,
                  jobStream
                );
                logger.info("Created job " + jobName + " from BuildConfig " + namespacedName + " with revision: " + resourceVersion);
              } else {
                Source source = new StreamSource(jobStream);
                job.updateByXml(source);
                job.save();
                logger.info("Updated job " + jobName + " from BuildConfig " + namespacedName + " with revision: " + resourceVersion);
              }
            }
          } else {
            logger.info("Ignored out of order notification for BuildConfig " + namespacedName
              + " with resourceVersion " + resourceVersion + " when we have already processed " + previousResourceVersion);
          }
        } finally {
          openshiftUpdatingJob.set(false);
        }
      }
    }
  }

  @SuppressFBWarnings("NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE")
  private void modifyJob(BuildConfig buildConfig) throws IOException, InterruptedException {
    if (isJenkinsBuildConfig(buildConfig)) {
      upsertJob(buildConfig);
      return;
    }

    // no longer a Jenkins build so lets delete it if it exists
    deleteJob(buildConfig);
  }

  @SuppressFBWarnings("NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE")
  private void deleteJob(BuildConfig buildConfig) throws IOException, InterruptedException {
    String jobName = OpenShiftUtils.jenkinsJobName(buildConfig, defaultNamespace);
    NamespaceName namespaceName = NamespaceName.create(buildConfig);

    synchronized (buildConfigVersions) {
      Job job = Jenkins.getInstance().getItem(jobName, Jenkins.getInstance(), Job.class);
      if (job != null) {
        job.delete();
        try {
          Jenkins.getInstance().reload();
        } catch (ReactorException e) {
          logger.log(Level.SEVERE, "Failed to reload jenkins job after deleting " + jobName + " from BuildConfig " + namespaceName);
        }
      }
      buildConfigVersions.remove(namespaceName);
    }
  }

  public static Long getResourceVersion(HasMetadata hasMetadata) {
    ObjectMeta metadata = hasMetadata.getMetadata();
    String resourceVersionText = metadata.getResourceVersion();
    Long resourceVersion = null;
    if (resourceVersionText != null && resourceVersionText.length() > 0) {
      resourceVersion = Long.parseLong(resourceVersionText);
    }
    return resourceVersion;
  }
}
