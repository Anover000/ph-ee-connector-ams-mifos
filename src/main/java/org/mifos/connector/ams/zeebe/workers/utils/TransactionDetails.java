package org.mifos.connector.ams.zeebe.workers.utils;

import java.time.LocalDateTime;

public record TransactionDetails(Object savings_account_id, Object amsTransactionId, String internalCorrelationId, String camt052, LocalDateTime created_at, LocalDateTime updated_at) {
}
