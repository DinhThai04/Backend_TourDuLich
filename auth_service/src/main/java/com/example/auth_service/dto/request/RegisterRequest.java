package com.example.auth_service.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.*;
import lombok.Data;

/** Public registration never accepts a role chosen by the caller. */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class RegisterRequest {
    @NotBlank(message = "Tên tài khoản không được để trống")
    @Size(min = 2, max = 50, message = "Tên tài khoản phải từ 2 đến 50 ký tự")
    @Pattern(regexp = "[\\p{L}\\p{M}]+(?: +[\\p{L}\\p{M}]+)*", message = "Tên tài khoản chỉ gồm chữ và khoảng trắng")
    private String username;
    @NotBlank(message = "Email không được để trống")
    @Email(message = "Email không hợp lệ")
    @Size(max = 254, message = "Email không được quá 254 ký tự")
    @Pattern(regexp = "(?i)[^\\s<>@]+@(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\\.)+[a-z]{2,}", message = "Email cần tên miền hợp lệ")
    private String email;
    @NotBlank(message = "Số điện thoại không được để trống")
    @Pattern(regexp = "0[0-9]{9,10}", message = "Số điện thoại bắt đầu bằng 0 và gồm 10–11 chữ số")
    private String phone;
    @NotBlank(message = "Địa chỉ không được để trống")
    @Size(max = 255, message = "Địa chỉ không được quá 255 ký tự")
    @Pattern(regexp = "[\\p{L}\\p{M}\\p{N} .,/()–-]+", message = "Địa chỉ chứa ký tự không hợp lệ")
    private String address;
    @NotBlank(message = "Mật khẩu không được để trống")
    @Size(min = 8, max = 72, message = "Mật khẩu phải từ 8 đến 72 ký tự")
    @Pattern(regexp = "(?s)(?=.*[A-Z])(?=.*[a-z])(?=.*[0-9])(?=.*[^\\p{L}\\p{N}\\s]).+", message = "Mật khẩu cần chữ hoa, chữ thường, số và ký tự đặc biệt")
    private String password;

    public void setUsername(String value) { username = trim(value); }
    public void setEmail(String value) { email = trim(value); }
    public void setPhone(String value) { phone = trim(value); }
    public void setAddress(String value) { address = trim(value); }
    private static String trim(String value) { return value == null ? null : value.trim(); }
}
