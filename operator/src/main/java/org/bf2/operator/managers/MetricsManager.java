package org.bf2.operator.managers;

import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.search.Search;
import io.quarkus.runtime.Startup;
import io.strimzi.api.kafka.KafkaList;
import io.strimzi.api.kafka.model.Kafka;
import io.strimzi.api.kafka.model.listener.arraylistener.GenericKafkaListener;
import org.bf2.common.ResourceInformer;
import org.bf2.common.ResourceInformerFactory;
import org.bf2.operator.operands.KafkaCluster;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import java.util.stream.IntStream;

import static org.bf2.operator.managers.InformerManager.filter;

@Startup
@ApplicationScoped
public class MetricsManager {
    static final String KAFKA_INSTANCE_BROKERS_DESIRED_COUNT = "kafka_instance_brokers_desired_count";
    static final String KAFKA_INSTANCE_PARTITION_LIMIT = "kafka_instance_partition_limit";
    static final String KAFKA_INSTANCE_MAX_MESSAGE_SIZE_LIMIT = "kafka_instance_max_message_size_limit";
    static final String KAFKA_INSTANCE_CONNECTION_LIMIT = "kafka_instance_connection_limit";
    static final String KAFKA_INSTANCE_CONNECTION_CREATION_RATE_LIMIT = "kafka_instance_connection_creation_rate_limit";
    static final Tag OWNER = Tag.of("owner", "KafkaInstanceMetricsManager");

    @Inject
    ResourceInformerFactory resourceInformerFactory;

    @Inject
    MeterRegistry meterRegistry;

    @Inject
    KubernetesClient kubernetesClient;

    private volatile ResourceInformer<Kafka> kafkaResourceInformer;

    protected synchronized void createInformer() {
        if (kafkaResourceInformer != null) {
            return;
        }
        kafkaResourceInformer = resourceInformerFactory.create(Kafka.class, filter(kubernetesClient.resources(Kafka.class, KafkaList.class)), new ResourceEventHandler<>() {
            @Override
            public void onAdd(Kafka kafka) {
                createMetrics(kafka);
            }

            @Override
            public void onUpdate(Kafka oldObj, Kafka newObj) {
                createMetrics(newObj);
            }

            @Override
            public void onDelete(Kafka obj, boolean deletedFinalStateUnknown) {
                Search.in(meterRegistry).tags(buildKafkaInstanceTags(obj)).meters().forEach(meterRegistry::remove);

            }

            private void createMetrics(Kafka kafka) {
                Tags tags = buildKafkaInstanceTags(kafka);
                meterRegistry.gauge(KAFKA_INSTANCE_BROKERS_DESIRED_COUNT, tags, kafka, this::replicas);
                meterRegistry.gauge(KAFKA_INSTANCE_PARTITION_LIMIT, tags, kafka, k -> kafkaConfigValue(k, KafkaCluster.MAX_PARTITIONS));
                meterRegistry.gauge(KAFKA_INSTANCE_MAX_MESSAGE_SIZE_LIMIT, tags, kafka, k -> kafkaConfigValue(k, KafkaCluster.MESSAGE_MAX_BYTES));

                if (kafka.getSpec() != null && kafka.getSpec().getKafka() != null) {
                    IntStream.range(0, kafka.getSpec().getKafka().getReplicas()).forEach(ordinal -> {
                        Tags brokerTags = Tags.concat(tags, Tags.of(Tag.of("broker", String.valueOf(ordinal))));

                        if (kafka.getSpec().getKafka().getListeners() != null) {
                            kafka.getSpec().getKafka().getListeners().stream().filter(l -> "external".equals(l.getName())).forEach(l -> {
                                Tags listenerTags = Tags.concat(brokerTags, Tags.of(Tag.of("listener", l.getName())));
                                meterRegistry.gauge(KAFKA_INSTANCE_CONNECTION_LIMIT, listenerTags, l, this::maxConnections);
                                meterRegistry.gauge(KAFKA_INSTANCE_CONNECTION_CREATION_RATE_LIMIT, listenerTags, l, this::maxConnectionCreationRate);
                            });
                        }
                    });
                }
            }

            private Tags buildKafkaInstanceTags(Kafka obj) {
                ObjectMeta metadata = obj.getMetadata();
                return Tags.of(Tag.of("namespace", metadata.getNamespace()), Tag.of("name", metadata.getName()), OWNER);
            }

            private Double replicas(Kafka k) {
                return k.getSpec() == null || k.getSpec().getKafka() == null ? Double.NaN : k.getSpec().getKafka().getReplicas();
            }

            private Double kafkaConfigValue(Kafka k, String configKey) {
                return k.getSpec() == null || k.getSpec().getKafka() == null || k.getSpec().getKafka().getConfig() == null || k.getSpec().getKafka().getConfig().get(configKey) == null ? Double.NaN : ((Number) k.getSpec().getKafka().getConfig().get(configKey)).doubleValue();
            }

            private Double maxConnections(GenericKafkaListener l) {
                return l.getConfiguration() == null ? Double.MAX_VALUE : l.getConfiguration().getMaxConnections();
            }

            private Double maxConnectionCreationRate(GenericKafkaListener l) {
                return l.getConfiguration() == null ? Double.MAX_VALUE : l.getConfiguration().getMaxConnectionCreationRate();
            }
        });
    }
}

