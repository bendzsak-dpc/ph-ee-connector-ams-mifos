package org.mifos.connector.ams.zeebe.workers.bookamount;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.mifos.connector.ams.fineract.Config;
import org.mifos.connector.ams.fineract.ConfigFactory;
import org.mifos.connector.ams.mapstruct.Pain001Camt053Mapper;
import org.mifos.connector.ams.zeebe.workers.utils.BatchItemBuilder;
import org.mifos.connector.ams.zeebe.workers.utils.TransactionBody;
import org.mifos.connector.ams.zeebe.workers.utils.DtSavingsTransactionDetails;
import org.mifos.connector.ams.zeebe.workers.utils.TransactionItem;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.camunda.zeebe.client.api.response.ActivatedJob;
import io.camunda.zeebe.client.api.worker.JobClient;
import io.camunda.zeebe.spring.client.annotation.JobWorker;
import io.camunda.zeebe.spring.client.annotation.Variable;
import io.camunda.zeebe.spring.client.exception.ZeebeBpmnError;
import iso.std.iso._20022.tech.json.camt_053_001.ReportEntry10;
import iso.std.iso._20022.tech.json.pain_001_001.Pain00100110CustomerCreditTransferInitiationV10MessageSchema;

@Component
public class OnUsTransferWorker extends AbstractMoneyInOutWorker {
	
	private static final String ERROR_FAILED_CREDIT_TRANSFER = "Error_FailedCreditTransfer";

	@Autowired
	private Pain001Camt053Mapper camt053Mapper;
	
	@Value("${fineract.incoming-money-api}")
	protected String incomingMoneyApi;
	
	@Autowired
    private ConfigFactory paymentTypeConfigFactory;
	
	@Autowired
	private BatchItemBuilder batchItemBuilder;
	
	private static final DateTimeFormatter PATTERN = DateTimeFormatter.ofPattern(FORMAT);

	@JobWorker
	public Map<String, Object> transferTheAmountBetweenDisposalAccounts(JobClient jobClient, 
			ActivatedJob activatedJob,
			@Variable String internalCorrelationId,
			@Variable String paymentScheme,
			@Variable String originalPain001,
			@Variable BigDecimal amount,
			@Variable Integer creditorDisposalAccountAmsId,
			@Variable Integer debtorDisposalAccountAmsId,
			@Variable Integer debtorConversionAccountAmsId,
			@Variable BigDecimal transactionFeeAmount,
			@Variable String tenantIdentifier,
			@Variable String transactionGroupId,
			@Variable String transactionCategoryPurposeCode,
			@Variable String transactionFeeCategoryPurposeCode,
			@Variable String transactionFeeInternalCorrelationId,
			@Variable String creditorIban,
			@Variable String debtorIban) {
		try {
			
			logger.debug("Incoming pain.001: {}", originalPain001);
			
			ObjectMapper objectMapper = new ObjectMapper();
			objectMapper.setSerializationInclusion(Include.NON_NULL);
			Pain00100110CustomerCreditTransferInitiationV10MessageSchema pain001 = objectMapper.readValue(originalPain001, Pain00100110CustomerCreditTransferInitiationV10MessageSchema.class);
			
			ReportEntry10 convertedcamt053Entry = camt053Mapper.toCamt053Entry(pain001.getDocument());
			String camt053Entry = objectMapper.writeValueAsString(convertedcamt053Entry);
			
			String interbankSettlementDate = LocalDate.now().format(PATTERN);
			
            batchItemBuilder.tenantId(tenantIdentifier);
    		
    		String debtorDisposalWithdrawalRelativeUrl = String.format("%s%d/transactions?command=%s", incomingMoneyApi.substring(1), debtorDisposalAccountAmsId, "withdrawal");
    		
    		Config paymentTypeConfig = paymentTypeConfigFactory.getConfig(tenantIdentifier);
    		String withdrawAmountOperation = "transferTheAmountBetweenDisposalAccounts.Debtor.DisposalAccount.WithdrawTransactionAmount";
			String withdrawAmountConfigOperationKey = String.format("%s.%s", paymentScheme, withdrawAmountOperation);
			Integer paymentTypeId = paymentTypeConfig.findPaymentTypeIdByOperation(withdrawAmountConfigOperationKey);
			String paymentTypeCode = paymentTypeConfig.findPaymentTypeCodeByOperation(withdrawAmountConfigOperationKey);
    		
    		TransactionBody body = new TransactionBody(
    				interbankSettlementDate,
    				amount,
    				paymentTypeId,
    				"",
    				FORMAT,
    				locale);
    		
    		String bodyItem = objectMapper.writeValueAsString(body);
    		
    		List<TransactionItem> items = new ArrayList<>();
    		
    		batchItemBuilder.add(items, debtorDisposalWithdrawalRelativeUrl, bodyItem, false);
    	
    		String camt053RelativeUrl = "datatables/transaction_details/$.resourceId";
    		
    		DtSavingsTransactionDetails td = new DtSavingsTransactionDetails(
    				internalCorrelationId,
    				camt053Entry,
    				debtorIban,
    				paymentTypeCode,
    				transactionGroupId,
    				pain001.getDocument().getPaymentInformation().get(0).getCreditTransferTransactionInformation().get(0).getCreditor().getName(),
    				pain001.getDocument().getPaymentInformation().get(0).getCreditTransferTransactionInformation().get(0).getCreditorAccount().getIdentification().getIban(),
    				null,
    				pain001.getDocument().getPaymentInformation().get(0).getCreditTransferTransactionInformation().get(0).getCreditor().getContactDetails().toString(),
    				pain001.getDocument().getPaymentInformation().get(0).getCreditTransferTransactionInformation().get(0).getRemittanceInformation().getUnstructured().toString(),
    				transactionCategoryPurposeCode);
    		
    		String camt053Body = objectMapper.writeValueAsString(td);

    		batchItemBuilder.add(items, camt053RelativeUrl, camt053Body, true);
    		
			
			if (!BigDecimal.ZERO.equals(transactionFeeAmount)) {
				String withdrawFeeDisposalOperation = "transferTheAmountBetweenDisposalAccounts.Debtor.DisposalAccount.WithdrawTransactionFee";
				String withdrawFeeDisposalConfigOperationKey = String.format("%s.%s", paymentScheme, withdrawFeeDisposalOperation);
				paymentTypeId = paymentTypeConfig.findPaymentTypeIdByOperation(withdrawFeeDisposalConfigOperationKey);
				paymentTypeCode = paymentTypeConfig.findPaymentTypeCodeByOperation(withdrawFeeDisposalConfigOperationKey);
	    		
	    		body = new TransactionBody(
	    				interbankSettlementDate,
	    				transactionFeeAmount,
	    				paymentTypeId,
	    				"",
	    				FORMAT,
	    				locale);
	    		
	    		bodyItem = objectMapper.writeValueAsString(body);
	    		
	    		batchItemBuilder.add(items, debtorDisposalWithdrawalRelativeUrl, bodyItem, false);
	    	
	    		convertedcamt053Entry.getEntryDetails().get(0).getTransactionDetails().get(0).getSupplementaryData().get(0).getEnvelope().setAdditionalProperty("InternalCorrelationId", transactionFeeInternalCorrelationId);
				camt053Entry = objectMapper.writeValueAsString(convertedcamt053Entry);
	    		
	    		td = new DtSavingsTransactionDetails(
	    				transactionFeeInternalCorrelationId,
	    				camt053Entry,
	    				debtorIban,
	    				paymentTypeCode,
	    				transactionGroupId,
	    				pain001.getDocument().getPaymentInformation().get(0).getCreditTransferTransactionInformation().get(0).getCreditor().getName(),
	    				pain001.getDocument().getPaymentInformation().get(0).getCreditTransferTransactionInformation().get(0).getCreditorAccount().getIdentification().getIban(),
	    				null,
	    				pain001.getDocument().getPaymentInformation().get(0).getCreditTransferTransactionInformation().get(0).getCreditor().getContactDetails().toString(),
	    				pain001.getDocument().getPaymentInformation().get(0).getCreditTransferTransactionInformation().get(0).getRemittanceInformation().getUnstructured().toString(),
	    				transactionFeeCategoryPurposeCode);
	    		
	    		camt053Body = objectMapper.writeValueAsString(td);
	    		batchItemBuilder.add(items, camt053RelativeUrl, camt053Body, true);

	    		
	    		
				String depositFeeOperation = "transferTheAmountBetweenDisposalAccounts.Debtor.ConversionAccount.DepositTransactionFee";
				String depositFeeConfigOperationKey = String.format("%s.%s", paymentScheme, depositFeeOperation);
				paymentTypeId = paymentTypeConfig.findPaymentTypeIdByOperation(depositFeeConfigOperationKey);
				paymentTypeCode = paymentTypeConfig.findPaymentTypeCodeByOperation(depositFeeConfigOperationKey);
	    		
	    		body = new TransactionBody(
	    				interbankSettlementDate,
	    				transactionFeeAmount,
	    				paymentTypeId,
	    				"",
	    				FORMAT,
	    				locale);
			    		
	    		bodyItem = objectMapper.writeValueAsString(body);
	    		
	    		String debtorConversionDepositRelativeUrl = String.format("%s%d/transactions?command=%s", incomingMoneyApi.substring(1), debtorConversionAccountAmsId, "deposit");
		    		
	    		batchItemBuilder.add(items, debtorConversionDepositRelativeUrl, bodyItem, false);
		    	
	    		td = new DtSavingsTransactionDetails(
	    				transactionFeeInternalCorrelationId,
	    				camt053Entry,
	    				creditorIban,
	    				paymentTypeCode,
	    				transactionGroupId,
	    				pain001.getDocument().getPaymentInformation().get(0).getCreditTransferTransactionInformation().get(0).getCreditor().getName(),
	    				pain001.getDocument().getPaymentInformation().get(0).getCreditTransferTransactionInformation().get(0).getCreditorAccount().getIdentification().getIban(),
	    				null,
	    				pain001.getDocument().getPaymentInformation().get(0).getCreditTransferTransactionInformation().get(0).getCreditor().getContactDetails().toString(),
	    				pain001.getDocument().getPaymentInformation().get(0).getCreditTransferTransactionInformation().get(0).getRemittanceInformation().getUnstructured().toString(),
	    				transactionFeeCategoryPurposeCode);
	    		
	    		camt053Body = objectMapper.writeValueAsString(td);
	    		batchItemBuilder.add(items, camt053RelativeUrl, camt053Body, true);
			}
			
			String depositAmountOperation = "transferTheAmountBetweenDisposalAccounts.Creditor.DisposalAccount.DepositTransactionAmount";
			String depositAmountConfigOperationKey = String.format("%s.%s", paymentScheme, depositAmountOperation);
			paymentTypeId = paymentTypeConfig.findPaymentTypeIdByOperation(depositAmountConfigOperationKey);
			paymentTypeCode = paymentTypeConfig.findPaymentTypeCodeByOperation(depositAmountConfigOperationKey);
    		
    		body = new TransactionBody(
    				interbankSettlementDate,
    				amount,
    				paymentTypeId,
    				"",
    				FORMAT,
    				locale);
		    		
    		bodyItem = objectMapper.writeValueAsString(body);
    		
    		String creditorDisposalDepositRelativeUrl = String.format("%s%d/transactions?command=%s", incomingMoneyApi.substring(1), creditorDisposalAccountAmsId, "deposit");
	    		
    		batchItemBuilder.add(items, creditorDisposalDepositRelativeUrl, bodyItem, false);
	    	
    		convertedcamt053Entry.getEntryDetails().get(0).getTransactionDetails().get(0).getSupplementaryData().get(0).getEnvelope().setAdditionalProperty("InternalCorrelationId", internalCorrelationId);
			camt053Entry = objectMapper.writeValueAsString(convertedcamt053Entry);
			
    		td = new DtSavingsTransactionDetails(
    				transactionFeeInternalCorrelationId,
    				camt053Entry,
    				creditorIban,
    				paymentTypeCode,
    				transactionGroupId,
    				pain001.getDocument().getPaymentInformation().get(0).getCreditTransferTransactionInformation().get(0).getCreditor().getName(),
    				pain001.getDocument().getPaymentInformation().get(0).getCreditTransferTransactionInformation().get(0).getCreditorAccount().getIdentification().getIban(),
    				null,
    				pain001.getDocument().getPaymentInformation().get(0).getCreditTransferTransactionInformation().get(0).getCreditor().getContactDetails().toString(),
    				pain001.getDocument().getPaymentInformation().get(0).getCreditTransferTransactionInformation().get(0).getRemittanceInformation().getUnstructured().toString(),
    				transactionCategoryPurposeCode);
    		
    		camt053Body = objectMapper.writeValueAsString(td);
    		batchItemBuilder.add(items, camt053RelativeUrl, camt053Body, true);
			
	    		
			if (!BigDecimal.ZERO.equals(transactionFeeAmount)) {
	    		String withdrawFeeConversionOperation = "transferTheAmountBetweenDisposalAccounts.Debtor.ConversionAccount.WithdrawTransactionFee";
				String withdrawFeeConversionConfigOperationKey = String.format("%s.%s", paymentScheme, withdrawFeeConversionOperation);
				paymentTypeId = paymentTypeConfig.findPaymentTypeIdByOperation(withdrawFeeConversionConfigOperationKey);
				paymentTypeCode = paymentTypeConfig.findPaymentTypeCodeByOperation(withdrawFeeConversionConfigOperationKey);
	    		
	    		body = new TransactionBody(
	    				interbankSettlementDate,
	    				transactionFeeAmount,
	    				paymentTypeId,
	    				"",
	    				FORMAT,
	    				locale);
			    		
	    		bodyItem = objectMapper.writeValueAsString(body);
	    		
	    		String debtorConversionWithdrawRelativeUrl = String.format("%s%d/transactions?command=%s", incomingMoneyApi.substring(1), debtorConversionAccountAmsId, "withdrawal");
		    		
	    		batchItemBuilder.add(items, debtorConversionWithdrawRelativeUrl, bodyItem, false);
		    	
	    		convertedcamt053Entry.getEntryDetails().get(0).getTransactionDetails().get(0).getSupplementaryData().get(0).getEnvelope().setAdditionalProperty("InternalCorrelationId", transactionFeeInternalCorrelationId);
				camt053Entry = objectMapper.writeValueAsString(convertedcamt053Entry);
				
				td = new DtSavingsTransactionDetails(
	    				transactionFeeInternalCorrelationId,
	    				camt053Entry,
	    				debtorIban,
	    				paymentTypeCode,
	    				transactionGroupId,
	    				pain001.getDocument().getPaymentInformation().get(0).getCreditTransferTransactionInformation().get(0).getCreditor().getName(),
	    				pain001.getDocument().getPaymentInformation().get(0).getCreditTransferTransactionInformation().get(0).getCreditorAccount().getIdentification().getIban(),
	    				null,
	    				pain001.getDocument().getPaymentInformation().get(0).getCreditTransferTransactionInformation().get(0).getCreditor().getContactDetails().toString(),
	    				pain001.getDocument().getPaymentInformation().get(0).getCreditTransferTransactionInformation().get(0).getRemittanceInformation().getUnstructured().toString(),
	    				transactionFeeCategoryPurposeCode);
	    		
	    		camt053Body = objectMapper.writeValueAsString(td);
			    		
	    		batchItemBuilder.add(items, camt053RelativeUrl, camt053Body, true);
			}
			
			doBatch(items, tenantIdentifier, internalCorrelationId);
			
			return Map.of("transactionDate", interbankSettlementDate);
		} catch (JsonProcessingException e) {
			logger.error(e.getMessage(), e);
			throw new ZeebeBpmnError(ERROR_FAILED_CREDIT_TRANSFER, e.getMessage());
		} catch (Exception e) {
			logger.error(e.getMessage(), e);
			throw new ZeebeBpmnError(activatedJob.getBpmnProcessId(), e.getMessage());
		}
	}
}
