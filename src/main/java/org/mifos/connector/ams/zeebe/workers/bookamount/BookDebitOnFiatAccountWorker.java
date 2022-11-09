package org.mifos.connector.ams.zeebe.workers.bookamount;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import io.camunda.zeebe.client.api.response.ActivatedJob;
import io.camunda.zeebe.client.api.worker.JobClient;

@Component
public class BookDebitOnFiatAccountWorker extends AbstractMoneyInOutWorker {
	
	Logger logger = LoggerFactory.getLogger(DebtorExchangeAndHoldWorker.class);
	
	private static final DateTimeFormatter PATTERN = DateTimeFormatter.ofPattern(FORMAT);

	@Override
	public void handle(JobClient jobClient, ActivatedJob activatedJob) throws Exception {
		Map<String, Object> variables = activatedJob.getVariablesAsMap();
		
		Integer fiatCurrencyAccountAmsId = (Integer) variables.get("fiatCurrencyAccountAmsId");
		Integer holdAmountId = (Integer) variables.get("holdAmountId");
		
		logger.info("Starting book debit on fiat account worker with currency account Id {} and hold amount Id {}", fiatCurrencyAccountAmsId, holdAmountId);
		
		ResponseEntity<Object> responseObject = release(fiatCurrencyAccountAmsId, holdAmountId);
		
		if (!HttpStatus.OK.equals(responseObject.getStatusCode())) {
			jobClient.newFailCommand(activatedJob.getKey()).retries(0).send();
			return;
		}
		
		
		BigDecimal amount = new BigDecimal(variables.get("amount").toString());
		AccountIdAmountPair[] debits = new AccountIdAmountPair[] { new AccountIdAmountPair(423, amount) };
		AccountIdAmountPair[] credits = new AccountIdAmountPair[] { new AccountIdAmountPair(422, amount) };
		
		JournalEntry entry = new JournalEntry(
				"1",
				(String) variables.get("currency"),
				debits,
				credits,
				"",
				LocalDate.now().format(PATTERN),
				"",
				String.format("%d", fiatCurrencyAccountAmsId),
				"",
				"",
				"",
				"",
				"",
				locale,
				FORMAT
				);
			var entity = new HttpEntity<>(entry, httpHeaders);
		
			var urlTemplate = UriComponentsBuilder.fromHttpUrl(fineractApiUrl)
				.path("/journalentries")
				.encode()
				.toUriString();
		
			responseObject = restTemplate.exchange(urlTemplate, HttpMethod.POST, entity, Object.class);
		
			if (!HttpStatus.OK.equals(responseObject.getStatusCode())) {
				logger.error("Debtor exchange and hold worker fails with status code {}", responseObject.getStatusCodeValue());
				jobClient.newFailCommand(activatedJob.getKey()).retries(0).send().join();
				return;
			}
			
			
			responseObject = withdraw(LocalDate.now().format(PATTERN), amount, fiatCurrencyAccountAmsId);
			
			if (!HttpStatus.OK.equals(responseObject.getStatusCode())) {
				logger.error("Debtor exchange and hold worker fails with status code {}", responseObject.getStatusCodeValue());
				jobClient.newFailCommand(activatedJob.getKey()).retries(0).send().join();
				return;
			}
		
			jobClient.newCompleteCommand(activatedJob.getKey()).variables(variables).send();
			
			logger.info("Book debit on fiat account has finished  successfully");
	}
}
