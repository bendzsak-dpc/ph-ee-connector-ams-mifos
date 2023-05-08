package org.mifos.connector.ams.zeebe.workers.bookamount;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBElement;

import org.jboss.logging.MDC;
import org.mifos.connector.ams.fineract.PaymentTypeConfig;
import org.mifos.connector.ams.fineract.PaymentTypeConfigFactory;
import org.mifos.connector.ams.mapstruct.Pacs008Camt053Mapper;
import org.mifos.connector.ams.zeebe.workers.utils.BatchItemBuilder;
import org.mifos.connector.ams.zeebe.workers.utils.TransactionBody;
import org.mifos.connector.ams.zeebe.workers.utils.TransactionDetails;
import org.mifos.connector.ams.zeebe.workers.utils.TransactionItem;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

import eu.nets.realtime247.ri_2015_10.ObjectFactory;
import io.camunda.zeebe.client.api.response.ActivatedJob;
import io.camunda.zeebe.client.api.worker.JobClient;
import iso.std.iso._20022.tech.json.camt_053_001.BankToCustomerStatementV08;

@Component
public class TransferToDisposalAccountWorker extends AbstractMoneyInOutWorker {
	
	@Autowired
	private Pacs008Camt053Mapper camt053Mapper;
	
	@Value("${fineract.incoming-money-api}")
	protected String incomingMoneyApi;
	
	@Value("${fineract.auth-token}")
	private String authToken;
	
	@Autowired
    private PaymentTypeConfigFactory paymentTypeConfigFactory;

	@Override
	@SuppressWarnings("unchecked")
	public void handle(JobClient jobClient, ActivatedJob activatedJob) throws Exception {
		try {
			Map<String, Object> variables = activatedJob.getVariablesAsMap();
			
			String originalPacs008 = (String) variables.get("originalPacs008");
			JAXBContext jc = JAXBContext.newInstance(ObjectFactory.class,
					iso.std.iso._20022.tech.xsd.pacs_008_001.ObjectFactory.class);
			JAXBElement<iso.std.iso._20022.tech.xsd.pacs_008_001.Document> object = (JAXBElement<iso.std.iso._20022.tech.xsd.pacs_008_001.Document>) jc.createUnmarshaller().unmarshal(new StringReader(originalPacs008));
			iso.std.iso._20022.tech.xsd.pacs_008_001.Document pacs008 = object.getValue();
		
			String internalCorrelationId = (String) variables.get("internalCorrelationId");
			String paymentScheme = (String) variables.get("paymentScheme");
			String transactionDate = (String) variables.get("transactionDate");
			MDC.put("internalCorrelationId", internalCorrelationId);
			
			String transactionGroupId = (String) variables.get("transactionGroupId");
			String transactionCategoryPurposeCode = (String) variables.get("transactionCategoryPurposeCode");

			logger.info("Exchange to e-currency worker has started");

			Object amount = variables.get("amount");
		
			Integer conversionAccountAmsId = (Integer) variables.get("conversionAccountAmsId");
			Integer disposalAccountAmsId = (Integer) variables.get("disposalAccountAmsId");
			
			String tenantId = (String) variables.get("tenantIdentifier");
			
			ObjectMapper om = new ObjectMapper();
			
			BatchItemBuilder biBuilder = new BatchItemBuilder(tenantId);
			
			String conversionAccountWithdrawRelativeUrl = String.format("%s%d/transactions?command=%s", incomingMoneyApi.substring(1), conversionAccountAmsId, "withdrawal");
			
			PaymentTypeConfig paymentTypeConfig = paymentTypeConfigFactory.getPaymentTypeConfig(tenantId);
			Integer paymentTypeId = paymentTypeConfig.findPaymentTypeByOperation(String.format("%s.%s", paymentScheme, "transferToDisposalAccount.ConversionAccount.WithdrawTransactionAmount"));
			
			TransactionBody body = new TransactionBody(
					transactionDate,
					amount,
					paymentTypeId,
					"",
					FORMAT,
					locale);
			
			String bodyItem = om.writeValueAsString(body);
			
			List<TransactionItem> items = new ArrayList<>();
			
			biBuilder.add(items, conversionAccountWithdrawRelativeUrl, bodyItem, false);
			
			BankToCustomerStatementV08 convertedCamt053 = camt053Mapper.toCamt053(pacs008);
			String camt053 = om.writeValueAsString(convertedCamt053);
			
			String camt053RelativeUrl = String.format("datatables/transaction_details/%d", conversionAccountAmsId);
			
			TransactionDetails td = new TransactionDetails(
					"$.resourceId",
					internalCorrelationId,
					camt053,
					transactionGroupId,
					transactionCategoryPurposeCode);
			
			String camt053Body = om.writeValueAsString(td);

			biBuilder.add(items, camt053RelativeUrl, camt053Body, true);
			
			
			String disposalAccountDepositRelativeUrl = String.format("%s%d/transactions?command=%s", incomingMoneyApi.substring(1), disposalAccountAmsId, "deposit");
			paymentTypeId = paymentTypeConfig.findPaymentTypeByOperation(String.format("%s.%s", paymentScheme, "transferToDisposalAccount.DisposalAccount.DepositTransactionAmount"));
			
			body = new TransactionBody(
					transactionDate,
					amount,
					paymentTypeId,
					"",
					FORMAT,
					locale);
			
			bodyItem = om.writeValueAsString(body);
			
			biBuilder.add(items, disposalAccountDepositRelativeUrl, bodyItem, false);
			
			camt053RelativeUrl = String.format("datatables/transaction_details/%d", disposalAccountAmsId);
			
			td = new TransactionDetails(
					"$.resourceId",
					internalCorrelationId,
					camt053,
					transactionGroupId,
					transactionCategoryPurposeCode);
			
			camt053Body = om.writeValueAsString(td);
			
			biBuilder.add(items, camt053RelativeUrl, camt053Body, true);
		
			doBatch(items, tenantId, internalCorrelationId);
			
			logger.info("Exchange to e-currency worker has finished successfully");
			jobClient.newCompleteCommand(activatedJob.getKey()).variables(variables).send();
		} catch (Exception e) {
			logger.error("Exchange to e-currency worker has failed, dispatching user task to handle exchange", e);
			jobClient.newThrowErrorCommand(activatedJob.getKey()).errorCode("Error_TransferToDisposalToBeHandledManually").send();
		} finally {
			MDC.remove("internalCorrelationId");
		}
	}
}
