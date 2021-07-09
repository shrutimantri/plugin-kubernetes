package io.kestra.plugin.kubernetes.services;

import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.dsl.PodResource;
import org.slf4j.Logger;

import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

abstract public class PodService {
    public static Pod waitForPodReady(KubernetesClient client, Pod pod, Duration waitUntilRunning) {
        return PodService.podRef(client, pod)
            .waitUntilCondition(
                j -> j.getStatus() == null ||
                    j.getStatus().getPhase().equals("Failed") ||
                    j.getStatus()
                        .getConditions()
                        .stream()
                        .filter(podCondition -> podCondition.getStatus().equalsIgnoreCase("True"))
                        .anyMatch(podCondition -> podCondition.getType().equals("ContainersReady") ||
                            (podCondition.getReason() != null && podCondition.getReason()
                                .equals("PodCompleted"))
                        ),
                waitUntilRunning.toSeconds(),
                TimeUnit.SECONDS
            );
    }

    public static void handleEnd(Pod ended) throws InterruptedException {
        // let some time to gather the logs before delete
        Thread.sleep(1000);

        if (ended.getStatus() != null && ended.getStatus().getPhase().equals("Failed")) {
            throw PodService.failedMessage(ended);
        }
    }

    public static Pod waitForCompletion(KubernetesClient client, Logger logger, Pod pod, Duration waitRunning) throws InterruptedException {
        Pod ended = null;
        PodResource<Pod> podResource = podRef(client, pod);

        while (ended == null) {
            try {
                ended = podResource
                    .waitUntilCondition(
                        j -> j == null || j.getStatus() == null || (!j.getStatus().getPhase().equals("Running") && !j.getStatus().getPhase().equals("Pending")),
                        waitRunning.toSeconds(),
                        TimeUnit.SECONDS
                    );
            } catch (KubernetesClientException e) {
                podResource = podRef(client, pod);

                if (podResource.get() != null) {
                    logger.debug("Pod is still alive, refreshing and trying to wait more", e);
                } else {
                    logger.warn("Unable to refresh pods, no pods was found!", e);
                    throw e;
                }
            }
        }

        return ended;
    }

    public static IllegalStateException failedMessage(Pod pod) throws IllegalStateException {
        if (pod.getStatus() == null) {
            return new IllegalStateException("Pods terminated without any status !");
        }

        return (pod.getStatus().getContainerStatuses() == null ? new ArrayList<ContainerStatus>() : pod.getStatus().getContainerStatuses())
            .stream()
            .filter(containerStatus -> containerStatus.getState() != null && containerStatus.getState().getTerminated() != null)
            .map(containerStatus -> containerStatus.getState().getTerminated())
            .findFirst()
            .map(containerStateTerminated -> new IllegalStateException(
                "Pods terminated with status '" + pod.getStatus().getPhase() + "', " +
                    "exitcode '" + containerStateTerminated.getExitCode() + "' & " +
                    "message '" + containerStateTerminated.getMessage() + "'"
            ))
            .orElse(new IllegalStateException("Pods terminated without any containers status !"));
    }

    public static PodResource<Pod> podRef(KubernetesClient client, Pod pod) {
        return client.pods()
            .inNamespace(pod.getMetadata().getNamespace())
            .withName(pod.getMetadata().getName());
    }
}
