package dev.agente.springai;

import dev.agente.core.domain.Decision;
import dev.agente.core.engine.AgentContext;
import dev.agente.core.port.Brain;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;

public final class SpringAiBrain implements Brain {

    private final ChatClient client;

    public SpringAiBrain(ChatModel model) {
        this.client = ChatClient.create(model);
    }

    @Override
    public Decision decide(AgentContext ctx) {
        String raw = client.prompt()
                .system(PromptFactory.system(ctx.tools()))
                .user(PromptFactory.user(ctx.goal(), ctx.history()))
                .call()
                .content();
        return DecisionParser.parse(raw);
    }
}
