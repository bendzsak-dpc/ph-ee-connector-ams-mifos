package org.mifos.connector.ams.zeebe.workers.utils;

import com.fasterxml.jackson.annotation.JsonProperty;

public record DtSavingsTransactionDetails(@JsonProperty("internal_correlation_id") String internalCorrelationId, 
		@JsonProperty("structured_transaction_details") String structuredTransactionDetails,
		@JsonProperty("account_iban") String accountIban,
		@JsonProperty("payment_type_code") String paymentTypeCode,
		@JsonProperty("transaction_group_id") String transactionGroupId,
		@JsonProperty("partner_name") String partnerName,
		@JsonProperty("category_purpose_code") String categoryPurposeCode) {
}
