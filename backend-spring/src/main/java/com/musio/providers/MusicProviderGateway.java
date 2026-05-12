package com.musio.providers;

import com.musio.agent.AgentRunContext;
import com.musio.model.ProviderType;
import com.musio.model.SourceContext;
import com.musio.providers.observation.ObservedMusicProvider;
import com.musio.providers.observation.ProviderCallObserver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Service
public class MusicProviderGateway {
    private final Map<ProviderType, MusicProvider> providers = new EnumMap<>(ProviderType.class);

    public MusicProviderGateway(List<MusicProvider> providerList) {
        this(providerList, null);
    }

    @Autowired
    public MusicProviderGateway(List<MusicProvider> providerList, ProviderCallObserver observer) {
        for (MusicProvider provider : providerList) {
            providers.put(provider.type(), observer == null ? provider : new ObservedMusicProvider(provider, observer));
        }
    }

    public MusicProvider defaultProvider() {
        return provider(AgentRunContext.sourceContextOrDefault());
    }

    public MusicProvider provider(SourceContext sourceContext) {
        SourceContext context = sourceContext == null ? SourceContext.defaultContext() : sourceContext;
        return provider(context.activeProviderType());
    }

    public MusicProvider provider(String sourceId) {
        return provider(ProviderType.fromSourceId(sourceId));
    }

    public MusicProvider provider(ProviderType type) {
        MusicProvider provider = providers.get(type);
        if (provider == null) {
            throw new IllegalArgumentException("Provider is not registered: " + type);
        }
        return provider;
    }

    public List<SourceCapability> capabilities(SourceContext sourceContext) {
        SourceContext context = sourceContext == null ? SourceContext.defaultContext() : sourceContext;
        MusicProvider provider = provider(context);
        if (provider instanceof MusicSourceProvider sourceProvider) {
            return sourceProvider.capabilities(context);
        }
        return MusicSourceDefaults.readCapabilities();
    }

    public Map<String, Object> execute(SourceToolCall call, SourceContext sourceContext) {
        SourceContext context = sourceContext == null ? SourceContext.defaultContext() : sourceContext;
        SourceToolCall routedCall = routeCall(call, context);
        MusicProvider provider = provider(context);
        if (provider instanceof MusicSourceProvider sourceProvider) {
            return sourceProvider.execute(routedCall, context);
        }
        return MusicSourceDefaults.executeLegacy(provider, routedCall);
    }

    private SourceToolCall routeCall(SourceToolCall call, SourceContext context) {
        SourceToolCall safeCall = call == null ? new SourceToolCall("", "", Map.of()) : call;
        String sourceId = safeCall.sourceId().isBlank() ? context.activeSource() : safeCall.sourceId();
        return new SourceToolCall(sourceId, safeCall.toolName(), safeCall.arguments());
    }
}
