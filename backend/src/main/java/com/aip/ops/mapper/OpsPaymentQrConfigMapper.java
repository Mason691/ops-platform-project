package com.aip.ops.mapper;

import com.aip.ops.entity.OpsPaymentQrConfig;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 收款码配置 Mapper。按 corp_id 显式查询（城市合伙人读取父运营商配置）。
 */
@Mapper
public interface OpsPaymentQrConfigMapper extends BaseMapper<OpsPaymentQrConfig> {

    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT * FROM ops_payment_qr_config "
            + "WHERE corp_id = #{corpId} AND delete_flag = 0 ORDER BY id DESC LIMIT 1")
    OpsPaymentQrConfig selectByCorp(@Param("corpId") Long corpId);

    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT * FROM ops_payment_qr_config "
            + "WHERE corp_id = #{corpId} ORDER BY delete_flag ASC, id DESC LIMIT 1")
    OpsPaymentQrConfig selectByCorpIncludingDeleted(@Param("corpId") Long corpId);

    /** 上传替换：复用同一行（含逻辑删除行），更新哈希/文件信息。 */
    @InterceptorIgnore(tenantLine = "true")
    @Update("UPDATE ops_payment_qr_config SET "
            + "file_name = #{fileName}, file_path = #{filePath}, file_hash = #{fileHash}, "
            + "file_size = #{fileSize}, content_type = #{contentType}, "
            + "uploader_user_id = #{uploaderUserId}, uploader_name = #{uploaderName}, "
            + "upload_time = #{uploadTime}, status = 1, delete_flag = 0, modify_time = NOW() "
            + "WHERE id = #{id} AND corp_id = #{corpId}")
    int updateExisting(OpsPaymentQrConfig config);
}
