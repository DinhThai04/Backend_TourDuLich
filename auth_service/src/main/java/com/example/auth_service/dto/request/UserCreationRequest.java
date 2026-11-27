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
    @Pattern(regexp = "[\\p{L}\\p{M}]+(?: +[\\p{L}\\p{M}]+)*", message = "Họ và tên chỉ gồm chữ và khoảng trắng")
    private String username;
    @NotBlank(message = "Địa chỉ không được để trống")
    @Size(max = 255, message = "Địa chỉ không được quá 255 ký tự")
    @Pattern(regexp = "[\\p{L}\\p{M}\\p{N} .,/()–-]+", message = "Địa chỉ chứa ký tự không hợp lệ")
    private String address;
    @Email(message = "Email không hợp lệ")
    @NotBlank(message = "Email không được để trống")
    @Size(max = 255, message = "Email không được quá 255 ký tự")
    @Pattern(regexp = "(?i)[^\\s<>@]+@(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\\.)+[a-z]{2,}", message = "Email cần tên miền hợp lệ")
    private String email;
    @Pattern(regexp = "0[0-9]{9,10}", message = "Số điện thoại bắt đầu bằng 0 và có 10–11 chữ số")
    private String phone;
    @NotBlank(message = "Mật khẩu không được để trống")
    @Size(min = 8, max = 72, message = "Mật khẩu phải từ 8 đến 72 ký tự")
    @Pattern(regexp = "(?s)(?=.*[A-Z])(?=.*[a-z])(?=.*[0-9])(?=.*[^\\p{L}\\p{N}\\s]).+", message = "Mật khẩu cần chữ hoa, chữ thường, số và ký tự đặc biệt")
    private String password;
    @jakarta.validation.constraints.Positive(message = "Mã vai trò phải là số dương")
    private Long roleId;

    public void setUsername(String value) { username = trim(value); }
    public void setEmail(String value) { email = trim(value); }
    public void setPhone(String value) { phone = value == null || value.isBlank() ? null : value.strip(); }
    public void setAddress(String value) { address = trim(value); }
    private static String trim(String value) { return value == null ? null : value.trim(); }

}
