package com.loyalty.auth.controller;

import com.loyalty.auth.dto.TokenResponse;
import com.loyalty.auth.exception.InvalidCredentialsException;
import com.loyalty.auth.exception.UserAlreadyExistsException;
import com.loyalty.auth.service.AuthService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "eureka.client.enabled=false")
class AuthControllerIT {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AuthService authService;

    @Test
    void registerWithValidPayloadReturns201() throws Exception {
        mockMvc.perform(post("/auth/register")
                        .contentType("application/json")
                        .content("{\"username\":\"newuser\",\"email\":\"newuser@loyalty.local\",\"password\":\"Password123!\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    void registerWithShortPasswordReturns400() throws Exception {
        mockMvc.perform(post("/auth/register")
                        .contentType("application/json")
                        .content("{\"username\":\"newuser\",\"email\":\"newuser@loyalty.local\",\"password\":\"short\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void registerDuplicateUserReturns409() throws Exception {
        doThrow(new UserAlreadyExistsException("ya existe"))
                .when(authService).register("dup", "dup@loyalty.local", "Password123!");

        mockMvc.perform(post("/auth/register")
                        .contentType("application/json")
                        .content("{\"username\":\"dup\",\"email\":\"dup@loyalty.local\",\"password\":\"Password123!\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void loginWithValidCredentialsReturns200WithToken() throws Exception {
        when(authService.login("test-user", "TestUser123!"))
                .thenReturn(new TokenResponse("access-tok", "refresh-tok", 300L));

        mockMvc.perform(post("/auth/login")
                        .contentType("application/json")
                        .content("{\"username\":\"test-user\",\"password\":\"TestUser123!\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("access-tok"));
    }

    @Test
    void loginWithInvalidCredentialsReturns401() throws Exception {
        when(authService.login("test-user", "wrong"))
                .thenThrow(new InvalidCredentialsException("invalido"));

        mockMvc.perform(post("/auth/login")
                        .contentType("application/json")
                        .content("{\"username\":\"test-user\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized());
    }
}
