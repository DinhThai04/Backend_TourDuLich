package com.example.auth_service.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import lombok.*;
import lombok.experimental.FieldDefaults;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@FieldDefaults(level = AccessLevel.PRIVATE)
public class UserCreationRequest {

    @NotBlank(message = "Username không được để trống")
    @Size(min = 3, max = 50, message = "Tên tài khoản phải từ 3 đến 50 ký tự")
    private String username;
    @NotBlank(message = "Địa chỉ không được để trống")
    @Size(max = 255, message = "Địa chỉ không được quá 255 ký tự")
    private String address;
    @Email(message = "Email không hợp lệ")
    @NotBlank(message = "Email không được để trống")
    @Size(max = 255, message = "Email không được quá 255 ký tự")
    private String email;
    @Pattern(regexp = "[0-9]{9,11}", message = "Số điện thoại phải có từ 9 đến 11 chữ số")
    private String phone;
    @NotBlank(message = "Mật khẩu không được để trống")
    @Size(min = 8, max = 72, message = "Mật khẩu phải từ 8 đến 72 ký tự")
    private String password;
    @jakarta.validation.constraints.Positive(message = "Mã vai trò phải là số dương")
    private Long roleId;

    public void setUsername(String value) { username = trim(value); }
    public void setEmail(String value) { email = trim(value); }
    public void setPhone(String value) { phone = trim(value); }
    public void setAddress(String value) { address = trim(value); }
    private static String trim(String value) { return value == null ? null : value.trim(); }

}
