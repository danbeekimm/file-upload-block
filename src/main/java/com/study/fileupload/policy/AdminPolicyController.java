package com.study.fileupload.policy;

import com.study.fileupload.common.Actor;
import com.study.fileupload.common.ActorResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/policies")
public class AdminPolicyController {

    private final PolicyService policyService;
    private final ActorResolver actorResolver;

    public AdminPolicyController(PolicyService policyService, ActorResolver actorResolver) {
        this.policyService = policyService;
        this.actorResolver = actorResolver;
    }

    @GetMapping
    public PolicyView view() {
        return policyService.view();
    }

    @PutMapping("/fixed/{ext}")
    public PolicyView.FixedItem toggleFixed(@PathVariable String ext,
                                            @Valid @RequestBody ToggleRequest body,
                                            HttpServletRequest request) {
        Actor actor = actorResolver.resolve(request);
        return policyService.toggleFixed(ext, body.blocked(), body.version(), actor);
    }

    @PostMapping("/custom")
    @ResponseStatus(HttpStatus.CREATED)
    public PolicyService.CustomAddResult addCustom(@Valid @RequestBody AddCustomRequest body,
                                                   HttpServletRequest request) {
        Actor actor = actorResolver.resolve(request);
        return policyService.addCustom(body.extension(), actor);
    }

    @DeleteMapping("/custom/{ext}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteCustom(@PathVariable String ext, HttpServletRequest request) {
        Actor actor = actorResolver.resolve(request);
        policyService.deleteCustom(ext, actor);
    }

    public record ToggleRequest(@NotNull Boolean blocked, @NotNull Integer version) {
    }

    /** 원본 입력 최대 길이는 넉넉히 받되(정규화로 줄 수 있음), 서버 정규화가 최종 판단 (명세 4-1) */
    public record AddCustomRequest(@NotNull @Size(max = 100) String extension) {
    }
}
