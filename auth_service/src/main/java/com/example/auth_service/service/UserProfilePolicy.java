package com.example.auth_service.service;

import com.example.auth_service.dto.request.UserCreationRequest;
import com.example.auth_service.dto.request.UserUpdate;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

final class UserProfilePolicy {
    private UserProfilePolicy() {}

    /** Bean validation already bounds length and format; the control-character rule is the one @Size cannot express. */
    static void validateCreation(UserCreationRequest request) {
        text(request.getUsername(), 3, 50, "Tên tài khoản");
        text(request.getAddress(), 1, 255, "Địa chỉ");
    }

    static void validate(UserUpdate request) {
        if(request==null) fail("Thiếu thông tin cập nhật");
        if(request.getUsername()!=null) request.setUsername(request.getUsername().trim());
        if(request.getAddress()!=null) request.setAddress(request.getAddress().trim());
        text(request.getUsername(),3,50,"Tên tài khoản");
        text(request.getAddress(),1,255,"Địa chỉ");
        if(request.getPhone()!=null && !request.getPhone().matches("[0-9]{9,11}"))
            fail("Số điện thoại phải có từ 9 đến 11 chữ số");
        if(request.getRoleId()!=null && request.getRoleId()<1) fail("Mã vai trò phải là số dương");
    }
    private static void text(String value,int min,int max,String name) {
        if(value!=null && (value.isBlank() || value.length()<min || value.length()>max
                || value.codePoints().anyMatch(c->Character.isISOControl(c) || Character.getType(c)==Character.FORMAT)))
            fail(name+" phải từ "+min+" đến "+max+" ký tự và không chứa ký tự điều khiển");
    }
    private static void fail(String message) {throw new ResponseStatusException(HttpStatus.BAD_REQUEST,message);}
}
