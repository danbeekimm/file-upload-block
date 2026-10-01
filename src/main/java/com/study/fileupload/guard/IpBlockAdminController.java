package com.study.fileupload.guard;

import com.study.fileupload.common.ActorResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/ip-blocks")
public class IpBlockAdminController {

    private final IpBlockService ipBlockService;
    private final ActorResolver actorResolver;

    public IpBlockAdminController(IpBlockService ipBlockService, ActorResolver actorResolver) {
        this.ipBlockService = ipBlockService;
        this.actorResolver = actorResolver;
    }

    @GetMapping
    public List<IpBlockView> listActive() {
        return ipBlockService.listActive().stream()
                .map(b -> new IpBlockView(b.getId(), b.getClientIp(), b.getScore(),
                        b.getBlockedAt(), b.getBlockedUntil()))
                .toList();
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void release(@PathVariable Long id, HttpServletRequest request) {
        ipBlockService.release(id, actorResolver.resolve(request).ip());
    }

    public record IpBlockView(Long id, String clientIp, int score, Instant blockedAt, Instant blockedUntil) {
    }
}
