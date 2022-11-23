package org.mifos.connector.ams.zeebe.workers.bookamount;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

import org.jboss.logging.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import io.camunda.zeebe.client.api.response.ActivatedJob;
import io.camunda.zeebe.client.api.worker.JobClient;

@Component
public class CreditorExchangeWorker extends AbstractMoneyInOutWorker {
	
	@Value("${fineract.paymentType.paymentTypeExchangeFiatCurrencyId}")
	private Integer paymentTypeExchangeFiatCurrencyId;
	
	@Value("${fineract.paymentType.paymentTypeIssuingECurrencyId}")
	private Integer paymentTypeIssuingECurrencyId;

	private static final DateTimeFormatter PATTERN = DateTimeFormatter.ofPattern(FORMAT);
	
	@Override
	public void handle(JobClient jobClient, ActivatedJob activatedJob) throws Exception {
		try {
			Map<String, Object> variables = activatedJob.getVariablesAsMap();
		
			String bicAndEndToEndId = (String) variables.get("bicAndEndToEndId");
			MDC.put("bicAndEndToEndId", bicAndEndToEndId);
		
			logger.info("Exchange to e-currency worker has started");
			
			LocalDateTime interbankSettlementDate = (LocalDateTime) variables.get("interbankSettlementDate");
		
			String transactionDate = interbankSettlementDate.format(PATTERN);
			Object amount = variables.get("amount");
		
			Integer fiatCurrencyAccountAmsId = (Integer) variables.get("fiatCurrencyAccountAmsId");
			Integer eCurrencyAccountAmsId = (Integer) variables.get("eCurrencyAccountAmsId");
		
			ResponseEntity<Object> responseObject = withdraw(transactionDate, amount, fiatCurrencyAccountAmsId, paymentTypeExchangeFiatCurrencyId);
		
			if (!HttpStatus.OK.equals(responseObject.getStatusCode())) {
				jobClient.newFailCommand(activatedJob.getKey()).retries(0).send();
				return;
			}
		
			responseObject = deposit(transactionDate, amount, eCurrencyAccountAmsId, paymentTypeIssuingECurrencyId);
		
			if (!HttpStatus.OK.equals(responseObject.getStatusCode())) {
				jobClient.newFailCommand(activatedJob.getKey()).retries(0).send().join();
				return;
			}
		
			logger.info("Exchange to e-currency worker has finished successfully");
			jobClient.newCompleteCommand(activatedJob.getKey()).variables(variables).send();
		} catch (Exception e) {
			logger.error("Exchange to e-currency worker has failed, dispatching user task to handle exchange", e);
			jobClient.newThrowErrorCommand(activatedJob.getKey()).errorCode("Error_ToBeHandledManually").send();
		} finally {
			MDC.remove("bicAndEndToEndId");
		}
	}
}
