package com.example.auth_service.dto.request;

import org.springframework.web.multipart.MultipartFile;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.FieldDefaults;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@FieldDefaults(level = AccessLevel.PRIVATE)
public class UserUpdate {
    @jakarta.validation.constraints.Size(min=3,max=50,message="Tên tài khoản phải từ 3 đến 50 ký tự")
    @jakarta.validation.constraints.Pattern(regexp="(?s).*\\S.*",message="Tên tài khoản không được để trống")
    String username;
    @jakarta.validation.constraints.Pattern(regexp="[0-9]{9,11}",message="Số điện thoại phải có từ 9 đến 11 chữ số")
    String phone;
    @jakarta.validation.constraints.Size(min=1,max=255,message="Địa chỉ phải từ 1 đến 255 ký tự")
    @jakarta.validation.constraints.Pattern(regexp="(?s).*\\S.*",message="Địa chỉ không được để trống")
    String address;
    @jakarta.validation.constraints.Positive(message="Mã vai trò phải là số dương")
    Long roleId;
    public void setUsername(String value) { username=value==null?null:value.strip(); }
    public void setAddress(String value) { address=value==null?null:value.strip(); }
}
