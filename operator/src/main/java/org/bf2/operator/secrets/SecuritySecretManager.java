package org.bf2.operator.secrets;

import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.Resource;
import org.bf2.common.OperandUtils;
import org.bf2.operator.InformerManager;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.stream.Collectors;

@ApplicationScoped
public class SecuritySecretManager {

    @Inject
    private KubernetesClient kubernetesClient;

    @Inject
    private InformerManager informerManager;

    public static boolean isKafkaAuthenticationEnabled(ManagedKafka managedKafka) {
        return managedKafka.getSpec().getOauth() != null;
    }

    public static boolean isKafkaExternalCertificateEnabled(ManagedKafka managedKafka) {
        return managedKafka.getSpec().getEndpoint().getTls() != null;
    }

    public static String kafkaTlsSecretName(ManagedKafka managedKafka) {
        return managedKafka.getMetadata().getName() + "-tls-secret";
    }

    public static String ssoClientSecretName(ManagedKafka managedKafka) {
        return managedKafka.getMetadata().getName() + "-sso-secret";
    }

    public static String ssoTlsSecretName(ManagedKafka managedKafka) {
        return managedKafka.getMetadata().getName() + "-sso-cert";
    }

    public static String kafkaClusterNamespace(ManagedKafka managedKafka) {
        return managedKafka.getMetadata().getNamespace();
    }

    public boolean isDeleted(ManagedKafka managedKafka) {
        boolean isDeleted = true;

        if (isKafkaExternalCertificateEnabled(managedKafka)) {
            isDeleted = cachedSecret(managedKafka, kafkaTlsSecretName(managedKafka)) == null;
        }

        if (isKafkaAuthenticationEnabled(managedKafka)) {
            isDeleted = isDeleted && cachedSecret(managedKafka, ssoClientSecretName(managedKafka)) == null &&
                    cachedSecret(managedKafka, ssoTlsSecretName(managedKafka)) == null;
        }

        return isDeleted;
    }

    public void createOrUpdate(ManagedKafka managedKafka) {
        Secret currentKafkaTlsSecret = cachedSecret(managedKafka, kafkaTlsSecretName(managedKafka));
        if (isKafkaExternalCertificateEnabled(managedKafka)) {
            Secret kafkaTlsSecret = kafkaTlsSecretFrom(managedKafka, currentKafkaTlsSecret);
            createOrUpdate(kafkaTlsSecret);
        } else if (currentKafkaTlsSecret != null) {
            secretResource(managedKafka, kafkaTlsSecretName(managedKafka)).delete();
        }

        Secret currentSsoClientSecret = cachedSecret(managedKafka, ssoClientSecretName(managedKafka));
        Secret currentSsoTlsSecret = cachedSecret(managedKafka, ssoTlsSecretName(managedKafka));

        if (isKafkaAuthenticationEnabled(managedKafka)) {
            Secret ssoClientSecret = ssoClientSecretFrom(managedKafka, currentSsoClientSecret);
            createOrUpdate(ssoClientSecret);

            if (managedKafka.getSpec().getOauth().getTlsTrustedCertificate() != null) {
                Secret ssoTlsSecret = ssoTlsSecretFrom(managedKafka, currentSsoTlsSecret);
                createOrUpdate(ssoTlsSecret);
            } else if (currentSsoTlsSecret != null) {
                secretResource(managedKafka, ssoTlsSecretName(managedKafka)).delete();
            }
        } else {
            if (currentSsoClientSecret != null) {
                secretResource(managedKafka, ssoClientSecretName(managedKafka)).delete();
            }
            if (currentSsoTlsSecret != null) {
                secretResource(managedKafka, ssoTlsSecretName(managedKafka)).delete();
            }
        }
    }

    public void delete(ManagedKafka managedKafka) {
        if (isKafkaExternalCertificateEnabled(managedKafka)) {
            secretResource(managedKafka, kafkaTlsSecretName(managedKafka)).delete();
        }

        if (isKafkaAuthenticationEnabled(managedKafka)) {
            secretResource(managedKafka, ssoClientSecretName(managedKafka)).delete();
            if (managedKafka.getSpec().getOauth().getTlsTrustedCertificate() != null) {
                secretResource(managedKafka, ssoTlsSecretName(managedKafka)).delete();
            }
        }
    }

    private Secret cachedSecret(ManagedKafka managedKafka, String name) {
        return informerManager.getLocalSecret(kafkaClusterNamespace(managedKafka), name);
    }

    private Resource<Secret> secretResource(ManagedKafka managedKafka, String name) {
        return kubernetesClient.secrets()
                .inNamespace(kafkaClusterNamespace(managedKafka))
                .withName(name);
    }

    private void createOrUpdate(Secret secret) {
        OperandUtils.createOrUpdate(kubernetesClient.secrets(), secret);
    }

    private static Secret buildSecretFrom(String name, String type, ManagedKafka managedKafka, Secret current, Map<String, String> dataSource) {
        SecretBuilder builder = current != null ? new SecretBuilder(current) : new SecretBuilder();

        Map<String, String> data = dataSource.entrySet()
                .stream()
                .map(entry -> Map.entry(entry.getKey(), encode(entry.getValue())))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        Secret secret = builder
                .editOrNewMetadata()
                    .withNamespace(kafkaClusterNamespace(managedKafka))
                    .withName(name)
                    .withLabels(OperandUtils.getDefaultLabels())
                .endMetadata()
                .withType(type)
                .withData(data)
                .build();

        // setting the ManagedKafka has owner of the Secret resource is needed
        // by the operator sdk to handle events on the Secret resource properly
        OperandUtils.setAsOwner(managedKafka, secret);

        return secret;
    }

    private static Secret kafkaTlsSecretFrom(ManagedKafka managedKafka, Secret current) {
        return buildSecretFrom(kafkaTlsSecretName(managedKafka),
                               "kubernetes.io/tls",
                               managedKafka,
                               current,
                               Map.of("tls.crt", managedKafka.getSpec().getEndpoint().getTls().getCert(),
                                      "tls.key", managedKafka.getSpec().getEndpoint().getTls().getKey()));
    }

    private static Secret ssoClientSecretFrom(ManagedKafka managedKafka, Secret current) {
        return buildSecretFrom(ssoClientSecretName(managedKafka),
                               "Opaque",
                               managedKafka,
                               current,
                               Map.of("ssoClientSecret", managedKafka.getSpec().getOauth().getClientSecret()));
    }

    private static Secret ssoTlsSecretFrom(ManagedKafka managedKafka, Secret current) {
        return buildSecretFrom(ssoTlsSecretName(managedKafka),
                               "Opaque",
                               managedKafka,
                               current,
                               Map.of("keycloak.crt", managedKafka.getSpec().getOauth().getTlsTrustedCertificate()));
    }

    private static String encode(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

}