package com.aip.ops.entity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data @TableName("gs_user")
public class GsUser {
    @TableId(type = IdType.AUTO) private Long id;
    @TableField("corp_id") private Long corpId;
    @TableField("phone") private String phone;
    @TableField("password") private String password;
    @TableField("openid") private String openid;
    @TableField("unionid") private String unionid;
    @TableField("nickname") private String nickname;
    @TableField("avatar") private String avatar;
    @TableField("points_balance") private Integer pointsBalance;
    @TableField("status") private Integer status;
    @TableField("last_login_time") private LocalDateTime lastLoginTime;
}
