package com.study.fileupload.policy;

import java.util.List;

/** GET /api/admin/policies 응답 */
public record PolicyView(List<FixedItem> fixed, List<CustomItem> custom, int customCount, int customMax) {

    public record FixedItem(String extension, boolean blocked, int version) {
    }

    public record CustomItem(String extension) {
    }
}
