package com.study.fileupload.guard;

import com.study.fileupload.config.UploadProperties;
import com.study.fileupload.upload.FileUploadRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IpBlockService {

    private static final Logger log = LoggerFactory.getLogger(IpBlockService.class);

    private final IpBlockRepository ipBlockRepository;
    private final FileUploadRepository fileUploadRepository;
    private final UploadProperties properties;

    public IpBlockService(IpBlockRepository ipBlockRepository,
                          FileUploadRepository fileUploadRepository,
                          UploadProperties properties) {
        this.ipBlockRepository = ipBlockRepository;
        this.fileUploadRepository = fileUploadRepository;
        this.properties = properties;
    }

    @Transactional(readOnly = true)
    public Optional<IpBlock> activeBlock(String ip) {
        return ipBlockRepository.findFirstActive(ip, Instant.now());
    }

    /**
     * 업로드 판정 기록 후 호출. 최근 창의 위반 점수 합이 임곗값 이상이면 일시 차단을 만든다.
     * 이미 활성 차단이 있으면 중복 생성하지 않는다.
     */
    @Transactional
    public void evaluate(String ip) {
        Instant now = Instant.now();
        if (ipBlockRepository.findFirstActive(ip, now).isPresent()) {
            return;
        }
        Instant since = now.minus(properties.ipBlock().window());
        long score = fileUploadRepository.sumRecentViolationScore(ip, since);
        if (score >= properties.ipBlock().threshold()) {
            Instant until = now.plus(properties.ipBlock().duration());
            ipBlockRepository.save(IpBlock.of(ip, (int) Math.min(score, Short.MAX_VALUE), until));
            log.warn("ip blocked: {} score={} until={}", ip, score, until);   // 의심 신호 (명세 11장)
        }
    }

    @Transactional(readOnly = true)
    public List<IpBlock> listActive() {
        return ipBlockRepository.findAllActive(Instant.now());
    }

    @Transactional
    public void release(Long id, String byIp) {
        ipBlockRepository.findById(id).ifPresent(block -> {
            if (block.getReleasedAt() == null) {
                block.release(byIp);
                log.info("ip block released: {} by {}", block.getClientIp(), byIp);
            }
        });
    }
}
