package io.fabric8.kubernetes.workflow;

import com.squareup.okhttp.Response;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.Volume;
import io.fabric8.kubernetes.api.model.VolumeBuilder;
import io.fabric8.kubernetes.api.model.VolumeMount;
import io.fabric8.kubernetes.api.model.VolumeMountBuilder;
import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.Watch;
import io.fabric8.kubernetes.client.dsl.ExecListener;
import io.fabric8.kubernetes.client.dsl.ExecWatch;
import io.fabric8.kubernetes.client.dsl.LogWatch;

import java.io.Closeable;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import static io.fabric8.kubernetes.workflow.Constants.EXIT;
import static io.fabric8.kubernetes.workflow.Constants.FAILED_PHASE;
import static io.fabric8.kubernetes.workflow.Constants.NEWLINE;
import static io.fabric8.kubernetes.workflow.Constants.RUNNING_PHASE;
import static io.fabric8.kubernetes.workflow.Constants.SPACE;
import static io.fabric8.kubernetes.workflow.Constants.SUCCEEDED_PHASE;
import static io.fabric8.kubernetes.workflow.Constants.UTF_8;
import static io.fabric8.kubernetes.workflow.Constants.VOLUME_PREFIX;

public final class KubernetesFacade {

    private static final KubernetesClient CLIENT = new DefaultKubernetesClient();
    private static final Map<String, Set<Closeable>> CLOSEABLES = new HashMap<>();

    public static io.fabric8.kubernetes.api.model.Pod createPod(String name, String image, String serviceAccount, Boolean privileged, Map<String, String> secrets, String workspace, List<EnvVar> env, String cmd) {
        List<Volume> volumes = new ArrayList<>();
        List<VolumeMount> mounts = new ArrayList<>();

        int volumeIndex = 1;

        //mandatory volumes
        volumes.add(new VolumeBuilder().withName(VOLUME_PREFIX + volumeIndex).withNewHostPath(workspace).build());
        mounts.add(new VolumeMountBuilder().withName(VOLUME_PREFIX + volumeIndex).withMountPath(workspace).build());
        volumeIndex++;

        for (Map.Entry<String, String> entry : secrets.entrySet()) {
            String secret = entry.getKey();
            String mountPath = entry.getValue();

            volumes.add(new VolumeBuilder()
                    .withName(VOLUME_PREFIX + volumeIndex)
                    .withNewSecret(secret)
                    .build());
            mounts.add(new VolumeMountBuilder()
                    .withName(VOLUME_PREFIX + volumeIndex)
                    .withMountPath(mountPath)
                    .build());

            volumeIndex++;
        }

        io.fabric8.kubernetes.api.model.Pod p = CLIENT.pods().createNew()
                .withNewMetadata()
                .withName(name)
                .addToLabels("owner", "jenkins")
                .endMetadata()
                .withNewSpec()
                .withVolumes(volumes)
                .addNewContainer()
                .withVolumeMounts(mounts)
                .withName("podstep")
                .withImage(image)
                .withEnv(env)
                .withWorkingDir(workspace)
                .withCommand("/bin/sh", "-c")
                .withArgs(cmd) // Always get the last part
                .withTty(true) //It screws up getLog() if tty = true
                .withNewSecurityContext()
                    .withPrivileged(privileged)
                .endSecurityContext()
                .withVolumeMounts(mounts)
                .endContainer()
                .withRestartPolicy("Never")
                .withServiceAccount(serviceAccount)
                .endSpec()
                .done();

        synchronized (CLOSEABLES) {
            CLOSEABLES.put(name, new HashSet<Closeable>());
        }
        return p;
    }

    public static Boolean deletePod(String podName) {
        if (CLIENT.pods().withName(podName).delete()) {
            synchronized (CLOSEABLES) {
                for (Closeable c : CLOSEABLES.remove(podName)) {
                    closeQuietly(c);
                }
            }
            return true;
        } else {
            return false;
        }
    }

    public static LogWatch watchLogs(String podName) {
        LogWatch watch = CLIENT.pods().withName(podName).watchLog();
        synchronized (CLOSEABLES) {
            CLOSEABLES.get(podName).add(watch);
        }
        return watch;
    }


    public static ExecWatch exec(String podName,  final AtomicBoolean alive, final CountDownLatch started, final CountDownLatch finished, final PrintStream out, final String... statements) {
        ExecWatch watch = CLIENT.pods().withName(podName)
                .redirectingInput()
                .writingOutput(out)
                .writingError(out)
                .withTTY()
                .usingListener(new ExecListener() {
                    @Override
                    public void onOpen(Response response) {
                        alive.set(true);
                        started.countDown();

                    }
                    @Override
                    public void onFailure(IOException e, Response response) {
                        alive.set(false);
                        e.printStackTrace(out);
                        started.countDown();
                        finished.countDown();
                    }

                    @Override
                    public void onClose(int i, String s) {
                        alive.set(false);
                        started.countDown();
                        finished.countDown();

                    }
                }).exec();

        synchronized (CLOSEABLES) {
            CLOSEABLES.get(podName).add(watch);
        }

        waitQuietly(started);

        try {
            for (String stmt : statements) {
                watch.getInput().write((stmt).getBytes(UTF_8));
                watch.getInput().write((SPACE).getBytes(UTF_8));
            }
            //We need to exit so that we know when the command has finished.
            watch.getInput().write(NEWLINE.getBytes(UTF_8));
            watch.getInput().write(EXIT.getBytes(UTF_8));
            watch.getInput().write(NEWLINE.getBytes(UTF_8));
            watch.getInput().flush();
        } catch (Exception e) {
            e.printStackTrace(out);
        }

        return watch;
    }

    public static Watch watch(final String podName, AtomicBoolean alive, CountDownLatch started, CountDownLatch finished, Boolean cleanUpOnFinish) {
        Callable<Void> onCompletion = cleanUpOnFinish ? new Callable<Void>() {
            @Override
            public Void call() throws Exception {
                cleanUp(podName);
                return null;
            }
        } : null;

        Watch watch = CLIENT.pods().withName(podName).watch(new PodWatcher(alive, started, finished, onCompletion));
        synchronized (CLOSEABLES) {
            CLOSEABLES.get(podName).add(watch);
        }
        return watch;
    }

    public static final boolean isPodRunning(io.fabric8.kubernetes.api.model.Pod pod) {
        return pod != null && pod.getStatus() != null && RUNNING_PHASE.equals(pod.getStatus().getPhase());
    }

    public static final boolean isPodCompleted(io.fabric8.kubernetes.api.model.Pod pod) {
        return pod != null && pod.getStatus() != null &&
                (SUCCEEDED_PHASE.equals(pod.getStatus().getPhase()) || FAILED_PHASE.equals(pod.getStatus().getPhase()));
    }


    private static void cleanUp(String podName) {
        synchronized (CLOSEABLES) {
            for (Closeable c : CLOSEABLES.remove(podName)) {
                closeQuietly(c);
            }
        }
    }

    private static void waitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            //ignore
        }
    }

    private static void closeQuietly(Closeable c) {
        try {
            c.close();
        } catch (IOException e) {
            //ignore
        }
    }
}