package com.musio.memory.context;

import com.musio.config.MusioConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class MemoryContextService {
    private static final Logger log = LoggerFactory.getLogger(MemoryContextService.class);

    private final DeterministicMemoryGuard deterministicGuard;
    private final LlmMemoryRoutePlanner llmRoutePlanner;
    private final MemoryReadPlanValidator validator;
    private final MemoryRetriever retriever;
    private final MemoryCompressor compressor;

    public MemoryContextService(
            DeterministicMemoryGuard deterministicGuard,
            LlmMemoryRoutePlanner llmRoutePlanner,
            MemoryReadPlanValidator validator,
            MemoryRetriever retriever,
            MemoryCompressor compressor
    ) {
        this.deterministicGuard = deterministicGuard;
        this.llmRoutePlanner = llmRoutePlanner;
        this.validator = validator;
        this.retriever = retriever;
        this.compressor = compressor;
    }

    public MemoryContextPackage build(MusioConfig.Ai ai, MemoryRouteRequest request) {
        if (request == null) {
            return MemoryContextPackage.empty();
        }
        try {
            MemoryReadPlan required = deterministicGuard == null ? MemoryReadPlan.empty() : deterministicGuard.requiredPlan(request);
            MemoryReadPlan dynamic = shouldUseLlmRoute(request) && llmRoutePlanner != null
                    ? llmRoutePlanner.route(ai, request)
                    : MemoryReadPlan.empty();
            MemoryReadPlan plan = validator == null ? required : validator.validate(required, dynamic);
            List<MemoryEvidence> evidence = retriever == null ? List.of() : retriever.retrieve(request, plan);
            MemoryContextPackage context = compressor == null
                    ? MemoryContextPackage.empty()
                    : compressor.compress(evidence, plan.tokenBudget());
            log.info(
                    "MEMORY_CONTEXT stage=memory_context runId={} userId={} readItems={} evidenceCount={} estimatedTokens={}",
                    com.musio.agent.AgentRunContext.runId().orElse("-"),
                    request.userId(),
                    plan.items().size(),
                    context.evidence().size(),
                    context.estimatedTokens()
            );
            return context;
        } catch (Exception e) {
            log.warn("Memory context pipeline failed", e);
            return MemoryContextPackage.empty();
        }
    }

    private boolean shouldUseLlmRoute(MemoryRouteRequest request) {
        if (request == null || request.goal() == null) {
            return false;
        }
        return request.goal().musicTask() || !request.goal().requiredOutcomes().isEmpty();
    }
}
