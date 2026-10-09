package com.premisave.wallet.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * All fields optional — service defaults startDate/endDate to the last
 * mpesa.daraja.pull-transactions.pull-days days (max 48h per Safaricom's
 * own retention window) and offsetValue to 0 if omitted.
 */
@Data
public class PullTransactionQueryRequest {
    private LocalDateTime startDate;
    private LocalDateTime endDate;
    private Integer offsetValue;
}