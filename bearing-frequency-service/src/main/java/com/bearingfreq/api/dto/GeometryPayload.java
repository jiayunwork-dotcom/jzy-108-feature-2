package com.bearingfreq.api.dto;

import com.bearingfreq.model.BearingGeometry;
import com.bearingfreq.validation.InvalidBearingInputException;

/**
 * 请求体中的轴承几何参数。contactAngleDeg 缺省为 0（深沟球轴承名义接触角）。
 */
public record GeometryPayload(
        Integer rollerCount,
        Double pitchDiameterMm,
        Double rollerDiameterMm,
        Double contactAngleDeg) {

    public BearingGeometry toGeometry() {
        if (rollerCount == null) {
            throw new InvalidBearingInputException("缺少字段 geometry.rollerCount（滚动体个数）");
        }
        if (pitchDiameterMm == null) {
            throw new InvalidBearingInputException("缺少字段 geometry.pitchDiameterMm（节圆直径 mm）");
        }
        if (rollerDiameterMm == null) {
            throw new InvalidBearingInputException("缺少字段 geometry.rollerDiameterMm（滚动体直径 mm）");
        }
        return new BearingGeometry(rollerCount, pitchDiameterMm, rollerDiameterMm,
                contactAngleDeg == null ? 0.0 : contactAngleDeg);
    }
}
