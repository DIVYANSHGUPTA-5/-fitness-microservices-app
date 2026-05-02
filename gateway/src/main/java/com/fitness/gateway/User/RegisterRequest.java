package com.fitness.gateway.User;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class RegisterRequest {
    @NotBlank(message = "Email is required")
    @Email(message = "Invalid email format")
    private String email;

    @NotBlank(message = "Password is Blank")
    @Size(min = 6,message = "Password must atleast have 6 character")
    private String password;

    private String keycloakId;
    private String firstName;
    private String lastName;
}
