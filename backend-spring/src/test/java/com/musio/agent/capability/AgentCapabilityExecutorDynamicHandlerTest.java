package com.musio.agent.capability;

import com.musio.agent.loop.AgentLoopState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentCapabilityExecutorDynamicHandlerTest {
    @Test
    void executesCapabilitiesThatAppearAfterExecutorConstruction() {
        RuntimeCapabilityHandler handler = new RuntimeCapabilityHandler();
        AgentCapabilityExecutor executor = new AgentCapabilityExecutor(List.of(handler));

        handler.exposeRuntimeTool();

        assertTrue(executor.canExecute("get_similar_songs"));
        assertTrue(executor.validate(null, "get_similar_songs", Map.of("songId", "qqmusic:1")).valid());
        assertEquals(
                "{\"success\":true,\"resultType\":\"songs\"}",
                executor.execute(null, "get_similar_songs", Map.of("songId", "qqmusic:1")).orElseThrow()
        );
    }

    private static final class RuntimeCapabilityHandler implements AgentCapabilityHandler {
        private boolean exposed;

        private void exposeRuntimeTool() {
            exposed = true;
        }

        @Override
        public List<AgentCapability> capabilities() {
            if (!exposed) {
                return List.of();
            }
            return List.of(new AgentCapability(
                    "get_similar_songs",
                    CapabilityEffect.READ,
                    "根据一首歌获取相似歌曲",
                    "{\"songId\": string}",
                    Set.of("songId")
            ));
        }

        @Override
        public boolean supports(String capabilityName) {
            return exposed && "get_similar_songs".equals(capabilityName);
        }

        @Override
        public Map<String, Object> normalizeArguments(
                String capabilityName,
                Map<String, Object> arguments,
                AgentCapabilityArgumentContext context
        ) {
            return arguments == null ? Map.of() : Map.copyOf(arguments);
        }

        @Override
        public AgentCapabilityValidationResult validateArguments(
                String capabilityName,
                Map<String, Object> arguments,
                AgentCapabilityArgumentContext context
        ) {
            return supports(capabilityName) && arguments != null && arguments.containsKey("songId")
                    ? AgentCapabilityValidationResult.accepted()
                    : AgentCapabilityValidationResult.rejected("missing_required_argument");
        }

        @Override
        public AgentCapabilityValidationResult validate(AgentLoopState state, String capabilityName, Map<String, Object> arguments) {
            return validateArguments(capabilityName, arguments, AgentCapabilityArgumentContext.defaultContext());
        }

        @Override
        public Optional<String> execute(AgentLoopState state, String capabilityName, Map<String, Object> arguments) {
            if (!supports(capabilityName)) {
                return Optional.empty();
            }
            return Optional.of("{\"success\":true,\"resultType\":\"songs\"}");
        }
    }
}
