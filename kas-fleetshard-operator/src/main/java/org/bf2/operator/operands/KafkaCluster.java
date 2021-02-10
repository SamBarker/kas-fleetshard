package org.bf2.operator.operands;

import io.fabric8.kubernetes.api.model.OwnerReference;
import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder;
import io.javaoperatorsdk.operator.api.Context;
import io.strimzi.api.kafka.model.Kafka;
import io.strimzi.api.kafka.model.KafkaBuilder;
import io.strimzi.api.kafka.model.listener.arraylistener.ArrayOrObjectKafkaListenersBuilder;
import io.strimzi.api.kafka.model.listener.arraylistener.GenericKafkaListenerBuilder;
import io.strimzi.api.kafka.model.listener.arraylistener.KafkaListenerType;
import io.strimzi.api.kafka.model.status.Condition;
import io.strimzi.api.kafka.model.storage.EphemeralStorageBuilder;
import org.bf2.operator.InformerManager;
import org.bf2.operator.clients.KafkaResourceClient;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Provides same functionalities to get a Kafka resource from a ManagedKafka one
 * and checking the corresponding status
 */
@ApplicationScoped
public class KafkaCluster implements Operand<ManagedKafka> {

    private static final Logger log = LoggerFactory.getLogger(KafkaCluster.class);

    @Inject
    KafkaResourceClient kafkaResourceClient;

    @Inject
    InformerManager informerManager;

    @Override
    public void createOrUpdate(ManagedKafka managedKafka) {
        Kafka kafka = kafkaFrom(managedKafka);
        // Kafka resource doesn't exist, has to be created
        if (kafkaResourceClient.getByName(kafka.getMetadata().getNamespace(), kafka.getMetadata().getName()) == null) {
            log.info("Creating Kafka instance {}/{}", kafka.getMetadata().getNamespace(), kafka.getMetadata().getName());
            kafkaResourceClient.create(kafka);
        // Kafka resource already exists, has to be updated
        } else {
            log.info("Updating Kafka instance {}", kafka.getSpec().getKafka().getVersion());
            kafkaResourceClient.createOrReplace(kafka);
        }
    }

    @Override
    public void delete(ManagedKafka managedKafka, Context<ManagedKafka> context) {
        kafkaResourceClient.delete(kafkaClusterNamespace(managedKafka), kafkaClusterName(managedKafka));
    }

    /* test */
    protected Kafka kafkaFrom(ManagedKafka managedKafka) {

        Map<String, Object> config = new HashMap<>();
        config.put("offsets.topic.replication.factor", 3);
        config.put("transaction.state.log.replication.factor", 3);
        config.put("transaction.state.log.min.isr", 2);
        config.put("log.message.format.version", managedKafka.getSpec().getVersions().getKafka());
        config.put("inter.broker.protocol.version", managedKafka.getSpec().getVersions().getKafka());

        Kafka kafka = new KafkaBuilder()
                .withNewApiVersion(Kafka.RESOURCE_GROUP + "/" + Kafka.V1BETA1)
                .withNewMetadata()
                    .withName(kafkaClusterName(managedKafka))
                    .withNamespace(kafkaClusterNamespace(managedKafka))
                    .withLabels(getLabels())
                .endMetadata()
                .withNewSpec()
                    .withNewKafka()
                        .withVersion(managedKafka.getSpec().getVersions().getKafka())
                        .withReplicas(3)
                        .withListeners(
                                new ArrayOrObjectKafkaListenersBuilder()
                                        .withGenericKafkaListeners(
                                                new GenericKafkaListenerBuilder()
                                                        .withName("plain")
                                                        .withPort(9092)
                                                        .withType(KafkaListenerType.INTERNAL)
                                                        .withTls(false)
                                                        .build()
                                        ).build()
                        )
                        .withStorage(new EphemeralStorageBuilder().build())
                        .withConfig(config)
                    .endKafka()
                    .withNewZookeeper()
                        .withReplicas(3)
                        .withStorage(new EphemeralStorageBuilder().build())
                    .endZookeeper()
                .endSpec()
                .build();

        // setting the ManagedKafka has owner of the Kafka resource is needed
        // by the operator sdk to handle events on the Kafka resource properly
        OwnerReference ownerReference = new OwnerReferenceBuilder()
                .withApiVersion(managedKafka.getApiVersion())
                .withKind(managedKafka.getKind())
                .withName(managedKafka.getMetadata().getName())
                .withUid(managedKafka.getMetadata().getUid())
                .build();
        kafka.getMetadata().setOwnerReferences(Collections.singletonList(ownerReference));

        return kafka;
    }

    private static Map<String, String> getLabels() {
        Map<String, String> labels = new HashMap<>(1);
        labels.put("app.kubernetes.io/managed-by", "kas-fleetshard-operator");
        return labels;
    }

    @Override
    public boolean isInstalling(ManagedKafka managedKafka) {
        Kafka kafka = informerManager.getLocalKafka(kafkaClusterNamespace(managedKafka), kafkaClusterName(managedKafka));
        Condition kafkaCondition = kafka.getStatus().getConditions().get(0);
        boolean isInstalling =
                kafkaCondition.getType().equals("NotReady")
                && kafkaCondition.getStatus().equals("True")
                && kafkaCondition.getReason().equals("Creating");
        log.info("KafkaCluster isInstalling = {}", isInstalling);
        return isInstalling;
    }

    @Override
    public boolean isReady(ManagedKafka managedKafka) {
        Kafka kafka = informerManager.getLocalKafka(kafkaClusterNamespace(managedKafka), kafkaClusterName(managedKafka));
        Condition kafkaCondition = kafka.getStatus().getConditions().get(0);
        boolean isReady = kafkaCondition.getType().equals("Ready") && kafkaCondition.getStatus().equals("True");
        log.info("KafkaCluster isReady = {}", isReady);
        return isReady;
    }

    @Override
    public boolean isError(ManagedKafka managedKafka) {
        Kafka kafka = informerManager.getLocalKafka(kafkaClusterNamespace(managedKafka), kafkaClusterName(managedKafka));
        Condition kafkaCondition = kafka.getStatus().getConditions().get(0);
        boolean isError =
                kafkaCondition.getType().equals("NotReady")
                && kafkaCondition.getStatus().equals("True")
                && !kafkaCondition.getReason().equals("Creating");
        log.info("KafkaCluster isError = {}", isError);
        return isError;
    }

    public static String kafkaClusterName(ManagedKafka managedKafka) {
        return managedKafka.getMetadata().getName();
    }

    public static String kafkaClusterNamespace(ManagedKafka managedKafka) {
        return managedKafka.getMetadata().getNamespace();
    }
}