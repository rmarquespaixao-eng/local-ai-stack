package dev.agente.app.config;

import dev.agente.core.application.AgentService;
import dev.agente.core.domain.AgentPolicy;
import dev.agente.core.engine.AgentEngine;
import dev.agente.core.engine.ToolRegistry;
import dev.agente.core.port.Brain;
import dev.agente.core.port.IdGenerator;
import dev.agente.core.port.RunRepository;
import dev.agente.core.port.Tool;
import dev.agente.springai.SpringAiBrain;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

@Configuration
public class AgentConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public ToolRegistry toolRegistry(List<Tool> tools) {
        return new ToolRegistry(tools);
    }

    @Bean
    public AgentPolicy agentPolicy(
            @Value("${agent.max-steps:8}") int maxSteps,
            @Value("${agent.max-consecutive-errors:2}") int maxConsecutiveErrors) {
        return new AgentPolicy(maxSteps, maxConsecutiveErrors);
    }

    @Bean
    public Brain brain(ChatModel chatModel) {
        return new SpringAiBrain(chatModel);
    }

    @Bean
    public AgentEngine agentEngine(Brain brain, ToolRegistry toolRegistry, AgentPolicy policy) {
        return new AgentEngine(brain, toolRegistry, policy);
    }

    @Bean
    public RunRepository runRepository() {
        return new InMemoryRunRepository();
    }

    @Bean
    public IdGenerator idGenerator() {
        return () -> UUID.randomUUID().toString();
    }

    @Bean
    public AgentService agentService(AgentEngine engine, RunRepository runs, IdGenerator ids) {
        return new AgentService(engine, runs, ids);
    }
}
