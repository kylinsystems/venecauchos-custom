/**
 * 
 */
package org.frontuari.events;

import java.math.BigDecimal;
import java.util.ArrayList;

import org.adempiere.base.event.AbstractEventHandler;
import org.adempiere.base.event.IEventTopics;
import org.adempiere.exceptions.AdempiereException;
import org.compiere.acct.Doc;
import org.compiere.acct.Fact;
import org.compiere.model.MAllocationHdr;
import org.compiere.model.MAllocationLine;
import org.compiere.model.MOrder;
import org.compiere.model.MPayment;
import org.compiere.model.MSysConfig;
import org.compiere.model.PO;
import org.compiere.util.CLogger;
import org.compiere.util.DB;
import org.compiere.util.Env;
import org.frontuari.model.MLVEMajorPlanLine;
import org.osgi.service.event.Event;

/**
 * Event Handler to Validate Payment from POS Payment
 * @autor Jorge Colmenarez, 20 sept. 2017, jcolmenarez@frontuari.com, Frontuari, C.A.
 *
 */
public class FTUEventsHandler extends AbstractEventHandler {
	
	CLogger log = CLogger.getCLogger(FTUEventsHandler.class);
	
	protected void initialize() {
		
		registerTableEvent(IEventTopics.DOC_BEFORE_PREPARE, MOrder.Table_Name);
		registerTableEvent(IEventTopics.DOC_BEFORE_POST, MPayment.Table_Name);
		registerTableEvent(IEventTopics.PO_BEFORE_NEW, MPayment.Table_Name);
		registerTableEvent(IEventTopics.DOC_BEFORE_POST, MAllocationHdr.Table_Name);
		registerTableEvent(IEventTopics.DOC_AFTER_REVERSECORRECT, MAllocationHdr.Table_Name);
	}

	protected void doHandleEvent(Event event) {
		PO po = getPO(event);
		String type = event.getTopic();
		if(po instanceof MOrder){
			if(type.equalsIgnoreCase(IEventTopics.DOC_BEFORE_PREPARE)){
				MOrder order = (MOrder)po;
				if(order.getPaymentRule().equals(order.PAYMENTRULE_MixedPOSPayment)){
					BigDecimal PaidAmt = Env.ZERO;
					String sql = "SELECT SUM(PayAmt) AS PaidAmt "
							+ "FROM C_POSPayment "
							+ "WHERE C_Order_ID = ? ";
					PaidAmt = DB.getSQLValueBD(null, sql, order.getC_Order_ID());
					if(PaidAmt == null)
						PaidAmt = Env.ZERO;
					//	Validate PaidAmt from POSPayment
					if(order.getGrandTotal().subtract(PaidAmt).compareTo(Env.ZERO) != 0){
						throw new AdempiereException("@POSPaymentDiffers@"
								+ " @GrandTotal@: "+order.getGrandTotal()
								+ " @PaidAmt@: "+PaidAmt
								+ " @amount.difference@: "+order.getGrandTotal().subtract(PaidAmt));
					}
				}
			}
		}
		//	Apply Distributions for InterOrg Accounts into Allocations
		else if(po instanceof MAllocationHdr){
			MAllocationHdr allocation = (MAllocationHdr)po;
			if(type.equalsIgnoreCase(IEventTopics.DOC_BEFORE_POST)){
				ApplyDistribution(allocation.getDoc());
			}
			else if(type.equalsIgnoreCase(IEventTopics.DOC_AFTER_REVERSECORRECT)){
				MAllocationLine[] allLines = allocation.getLines(true);
				for(MAllocationLine line : allLines){
					//	Check if have Major Plan Allocated for reverse Lines Paid
					if(line.get_Value("LVE_MajorPlanLine_ID") != null 
							|| line.get_ValueAsInt("LVE_MajorPlanLine_ID") > 0){
						MLVEMajorPlanLine mpLine = new MLVEMajorPlanLine(po.getCtx(), 
								line.get_ValueAsInt("LVE_MajorPlanLine_ID"), po.get_TrxName());
						mpLine.setIsPaid(false);
						mpLine.saveEx(po.get_TrxName());
					}
				}
			}
		}
		//	Apply Distributions for InterOrg Accounts into Payments
		else if(po instanceof MPayment){
			MPayment pay = (MPayment)po;
			if(type.equalsIgnoreCase(IEventTopics.DOC_BEFORE_POST)){
				ApplyDistribution(pay.getDoc());
			}
			else if(type.equalsIgnoreCase(IEventTopics.PO_BEFORE_NEW)){
				if(pay.getC_POSTenderType_ID()!=0 && pay.getC_Invoice_ID() != 0){
					int LVE_POSDocType_ID = MSysConfig.getIntValue("LVE_POSDocTypeId",0,pay.getAD_Client_ID(), pay.getAD_Org_ID());
					if(LVE_POSDocType_ID != 0){
						pay.setC_DocType_ID(LVE_POSDocType_ID);
					}
					else{
						LVE_POSDocType_ID = MSysConfig.getIntValue("LVE_POSDocTypeId",0,pay.getAD_Client_ID(), 0);
						if(LVE_POSDocType_ID != 0){
							pay.setC_DocType_ID(LVE_POSDocType_ID);
						}
					}
				}
			}
		}
	}
	
	/**
	 * This Method can apply a distribution for accounts created
	 * @autor Jorge Colmenarez, 24 sept. 2017, jcolmenarez@frontuari.com, Frontuari, C.A.
	 * @param doc
	 */
	public void ApplyDistribution(Doc doc){
		ArrayList<Fact> facts = doc.getFacts();
		// one fact per acctschema
		for (int i = 0; i < facts.size(); i++)
		{
			Fact fact = facts.get(i);
			if (!fact.distribute()){
				throw new AdempiereException("@PostingError-D@");
			}
		}
	}
}
