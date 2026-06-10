package com.aip.ops.mapper;
import com.aip.ops.entity.GsUser;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface GsUserMapper extends BaseMapper<GsUser> {

    @Select("<script>" +
            "SELECT m.id, m.name, m.phone, m.area, m.status " +
            "FROM mall_station_master m " +
            "WHERE m.delete_flag = 0 " +
            "<if test='keyword != null and keyword != \"\"'>" +
            "  AND (m.name LIKE CONCAT('%',#{keyword},'%') OR m.phone LIKE CONCAT('%',#{keyword},'%')) " +
            "</if>" +
            "ORDER BY m.id " +
            "LIMIT #{offset}, #{size}" +
            "</script>")
    List<Map<String, Object>> selectJoinedMerchants(@Param("offset") int offset,
                                                    @Param("size") int size,
                                                    @Param("keyword") String keyword);
}
