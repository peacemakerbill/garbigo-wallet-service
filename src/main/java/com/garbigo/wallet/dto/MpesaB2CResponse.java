package com.premisave.wallet.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class MpesaB2CResponse {
    private boolean success;
    private String message;
    private String conversationId;
    private String originatorConversationId;
}