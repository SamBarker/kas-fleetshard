package org.bf2.operator;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.KubernetesResourceList;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.FilterWatchListDeletable;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.informers.SharedInformerFactory;
import io.fabric8.kubernetes.client.informers.cache.Cache;
import io.fabric8.openshift.api.model.Route;
import io.fabric8.openshift.client.OpenShiftClient;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.strimzi.api.kafka.KafkaList;
import io.strimzi.api.kafka.model.Kafka;
import org.bf2.common.OperandUtils;
import org.bf2.common.ResourceInformer;
import org.bf2.operator.events.ResourceEventSource;
import org.jboss.logging.Logger;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.event.Observes;
import javax.inject.Inject;

@ApplicationScoped
public class InformerManager {

    @Inject
    Logger log;

    @Inject
    KubernetesClient kubernetesClient;

    @Inject
    ResourceEventSource eventSource;

    private SharedInformerFactory sharedInformerFactory;

    private ResourceInformer<Kafka> kafkaInformer;
    private ResourceInformer<Deployment> deploymentInformer;
    private ResourceInformer<Service> serviceInformer;
    private ResourceInformer<ConfigMap> configMapInformer;
    private ResourceInformer<Secret> secretInformer;
    private ResourceInformer<Route> routeInformer;


    boolean isOpenShift() {
        return kubernetesClient.isAdaptable(OpenShiftClient.class);
    }

    /**
     * Start each informer in a blocking manner.  The controller(s) will
     * not be initilized until after this completes - ensuring that all
     * will be synced to avoid any inconsistent state on start-up.
     *
     * This could be modified to start all in parallel, and then wait for sync.
     */
    void onStart(@Observes StartupEvent ev) {
        sharedInformerFactory = kubernetesClient.informers();

        kafkaInformer = ResourceInformer.start(filter(kubernetesClient.customResources(Kafka.class, KafkaList.class)), eventSource);

        deploymentInformer = ResourceInformer.start(filter(kubernetesClient.apps().deployments()), eventSource);

        serviceInformer = ResourceInformer.start(filter(kubernetesClient.services()), eventSource);

        configMapInformer = ResourceInformer.start(filter(kubernetesClient.configMaps()), eventSource);

        secretInformer = ResourceInformer.start(filter(kubernetesClient.secrets()), eventSource);

        if (isOpenShift()) {
            routeInformer = ResourceInformer.start(filter(kubernetesClient.adapt(OpenShiftClient.class).routes()), eventSource);
        }

        sharedInformerFactory.startAllRegisteredInformers();
    }

    static <T extends HasMetadata> FilterWatchListDeletable<T, ? extends KubernetesResourceList<T>> filter(
            MixedOperation<T, ? extends KubernetesResourceList<T>, ?> mixedOperation) {
        return mixedOperation.inAnyNamespace().withLabels(OperandUtils.getDefaultLabels());
    }

    void onStop(@Observes ShutdownEvent ev) {
        sharedInformerFactory.stopAllRegisteredInformers();
    }

    public Kafka getLocalKafka(String namespace, String name) {
        return kafkaInformer.getByKey(Cache.namespaceKeyFunc(namespace, name));
    }

    public Deployment getLocalDeployment(String namespace, String name) {
        return deploymentInformer.getByKey(Cache.namespaceKeyFunc(namespace, name));
    }

    public Service getLocalService(String namespace, String name) {
        return serviceInformer.getByKey(Cache.namespaceKeyFunc(namespace, name));
    }

    public ConfigMap getLocalConfigMap(String namespace, String name) {
        return configMapInformer.getByKey(Cache.namespaceKeyFunc(namespace, name));
    }

    public Secret getLocalSecret(String namespace, String name) {
        return secretInformer.getByKey(Cache.namespaceKeyFunc(namespace, name));
    }

    public Route getLocalRoute(String namespace, String name) {
        if (isOpenShift()) {
            return routeInformer.getByKey(Cache.namespaceKeyFunc(namespace, name));
        } else {
            log.warn("Not running on OpenShift cluster, Routes are not available");
            return null;
        }
    }

    public boolean isReady() {
        return kafkaInformer.isReady()
                && deploymentInformer.isReady()
                && serviceInformer.isReady()
                && configMapInformer.isReady()
                && secretInformer.isReady()
                && (!isOpenShift() || routeInformer.isReady());
    }
}
