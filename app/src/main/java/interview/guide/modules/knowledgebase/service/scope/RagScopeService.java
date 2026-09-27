package interview.guide.modules.knowledgebase.service.scope;

import interview.guide.modules.knowledgebase.model.RagChatSessionEntity;
import interview.guide.modules.knowledgebase.model.RagScopeKind;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 范围解析入口：按会话上的 kind 选解析器。
 * <p>
 * 每轮提问都会调用一次，因此会话内改范围对下一条提问立即生效。
 * </p>
 */
@Slf4j
@Service
public class RagScopeService {

    private final Map<RagScopeKind, RagScopeResolver> resolvers = new EnumMap<>(RagScopeKind.class);

    public RagScopeService(List<RagScopeResolver> resolverList) {
        for (RagScopeResolver resolver : resolverList) {
            resolvers.put(resolver.kind(), resolver);
        }
    }

    /**
     * 解析会话的检索范围；kind 缺省或未知时按「全部资料」处理。
     */
    public ResolvedScope resolve(RagChatSessionEntity session) {
        RagScopeKind kind = parseKind(session.getKbScope());
        RagScopeResolver resolver = resolvers.get(kind);
        if (resolver == null) {
            resolver = resolvers.get(RagScopeKind.ALL);
        }
        return resolver.resolve(session);
    }

    /**
     * 字符串 → 枚举；空值或非法值回退 ALL（老会话没有 scope 字段）。
     */
    public static RagScopeKind parseKind(String raw) {
        if (raw == null || raw.isBlank()) {
            return RagScopeKind.ALL;
        }
        try {
            return RagScopeKind.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("未知的检索范围 {}，按全部资料处理", raw);
            return RagScopeKind.ALL;
        }
    }
}
