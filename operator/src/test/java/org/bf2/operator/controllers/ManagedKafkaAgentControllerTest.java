package org.bf2.operator.controllers;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import javax.inject.Inject;

import org.bf2.common.AgentResourceClient;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaAgent;
import org.bf2.test.mock.QuarkusKubeMockServer;
import org.junit.jupiter.api.Test;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTestResource(QuarkusKubeMockServer.class)
@QuarkusTest
public class ManagedKafkaAgentControllerTest {

    @Inject
    ManagedKafkaAgentController mkaController;

    @Inject
    AgentResourceClient agentClient;

    @Test
    void shouldCreateStatus() {
        //try without an agent - nothing should happen
        mkaController.statusUpdateLoop();

        ManagedKafkaAgent dummyInstance = AgentResourceClient.getDummyInstance();
        dummyInstance.getMetadata().setNamespace(agentClient.getNamespace());
        assertNull(dummyInstance.getStatus());
        agentClient.create(dummyInstance);

        //should create the status even if
        mkaController.statusUpdateLoop();
        ManagedKafkaAgent agent = agentClient.getByName(agentClient.getNamespace(), AgentResourceClient.RESOURCE_NAME);
        assertNotNull(agent.getStatus());
    }

}