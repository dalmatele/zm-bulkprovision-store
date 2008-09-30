package com.zimbra.bp;

import com.zimbra.cs.service.admin.AdminDocumentHandler;
import com.zimbra.cs.service.FileUploadServlet;
import com.zimbra.cs.account.Provisioning;
import com.zimbra.cs.account.Account;
import com.zimbra.cs.account.Domain;
import com.zimbra.common.soap.Element;
import com.zimbra.common.service.ServiceException;
import com.zimbra.common.util.ZimbraLog;
import com.zimbra.common.util.ByteUtil;
import com.zimbra.soap.ZimbraSoapContext;

import java.util.Map;
import java.io.InputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.security.SecureRandom;

import au.com.bytecode.opencsv.CSVReader;

/**
 * Created by IntelliJ IDEA.
 * User: ccao
 * Date: Sep 23, 2008
 * Time: 11:16:06 AM
 * To change this template use File | Settings | File Templates.
 */
public class GetBulkProvisionAccounts extends AdminDocumentHandler {

    public static final String A_accountName = "accountName" ;
    public static final String A_displayName = "displayName" ;
    public static final String A_password = "password" ;
    public static final String A_status = "status" ;
    public static final String A_isValid = "isValid" ;
    public static final String A_isValidCSV = "isValidCSV" ;
    public static final String A_mustChangePassword = "mustChangePassword" ;
//    public static final String A_error = "error" ;

    public static final String ERROR_INVALID_ACCOUNT_NAME = "Invalid account name. " ;
    public boolean domainAuthSufficient(Map context) {
        return true;
    }

	public Element handle(Element request, Map<String, Object> context) throws ServiceException {

        String aid = request.getElement("aid").getText();
        //String aid = request.getAttribute("aid");
        ZimbraLog.extensions.debug("Uploaded CSV file id = " + aid) ;
        ZimbraSoapContext lc = getZimbraSoapContext(context);
        
        Element response = lc.createElement(ZimbraBulkProvisionService.GET_BULK_PROVISION_ACCOUNTS_RESPONSE);
        response.addElement("aid").addText(aid) ;

        FileUploadServlet.Upload up = FileUploadServlet.fetchUpload(lc.getAuthtokenAccountId(), aid, lc.getAuthToken());
        if (up == null)
           throw ServiceException.FAILURE("Uploaded CSV file with id " + aid + " was not found.", null);

        InputStream in = null ;
        boolean isValidCSV = true ;
        int lineNo = 1 ;
        try {
            in = up.getInputStream() ;
            CSVReader reader = new CSVReader(new InputStreamReader(in)) ;
            String [] nextLine ;

            Element el = null;

            String accountName, displayName, password, status ;
            ZimbraLog.extensions.debug("Read CSV file content.")  ;

            while ((nextLine = reader.readNext()) != null) {
                boolean isValidEntry = false ;
                accountName = displayName = password = status = null ;
                try {
                    isValidEntry = validEntry (nextLine, lc) ;
                }catch (ServiceException e) {
                    isValidCSV = false ;
                    ZimbraLog.extensions.error(e);
                    status = "Line " + lineNo + ": " + e.getMessage() ;
                }

                el = response.addElement("account") ;

                if (isValidEntry) {
                    accountName = nextLine [0] ;
                    displayName = nextLine [1] ;
                    password = nextLine [2] ;
    
                    el.addKeyValuePair(A_accountName, accountName) ;
                    el.addKeyValuePair(A_displayName, displayName) ;

                    if (password == null || password.trim().length() <=0) {
                        password = String.valueOf(generateStrongPassword(8)) ;
//                        ZimbraLog.extensions.debug("Generated Random Password: " + password) ;
                        el.addKeyValuePair(A_mustChangePassword, "TRUE") ;
                    }
                    el.addKeyValuePair(A_password, password.trim()) ;
                } 

                if (isValidEntry) {
                    el.addKeyValuePair(A_isValid, "TRUE");
                }else{
                    el.addKeyValuePair(A_isValid, "FALSE");
                }
                
                if ((status != null) && (status.length()>0)) {
                    el.addKeyValuePair(A_status, status) ;
                }

                lineNo ++ ;
            }

            in.close();

        }catch (IOException e) {
           throw ServiceException.FAILURE("", e) ;
        }finally {
            try {
                in.close ();
            }catch (IOException e) {
                ZimbraLog.extensions.error(e);                
            }
        }

        if (isValidCSV)  {
            response.addElement(A_isValidCSV).addText("TRUE") ;
        }else{
            response.addElement(A_isValidCSV).addText("FALSE") ;
        }

        return response;
	}

    private boolean validEntry (String [] entries, ZimbraSoapContext lc) throws ServiceException {
        Provisioning prov = Provisioning.getInstance();
        String errorMsg = "" ;
        if (entries.length != 3) {
            errorMsg = "Invalid number of columns." ;
            throw ServiceException.PARSE_ERROR(errorMsg, new Exception(errorMsg)) ;
        }

        String accountName = entries [0] ;

        //1. account name is specified and can be accessed by current admin/domain admin user
        if (accountName == null || accountName.length() <= 0) {
            throw ServiceException.PARSE_ERROR(ERROR_INVALID_ACCOUNT_NAME, new Exception(ERROR_INVALID_ACCOUNT_NAME)) ;
        }

        String parts[] = accountName.split("@");

        if (parts.length != 2)
            throw ServiceException.PARSE_ERROR(ERROR_INVALID_ACCOUNT_NAME, new Exception(ERROR_INVALID_ACCOUNT_NAME)) ;

        if (!canAccessEmail(lc, accountName)) {
            throw ServiceException.PERM_DENIED("Permission denied to create account " + accountName) ;
        }


        //2. if account already exists
        Account acct = null ;
        try {
            acct = prov.getAccount(accountName) ;
        }catch (Exception e) {
            //ignore
        }
        if (acct != null) {
            errorMsg = "Account " + accountName + " already exists." ;
            throw ServiceException.PARSE_ERROR(errorMsg, new Exception(errorMsg)) ;
        }

        String domain = parts[1];

        //domain exists
        Domain d = null ;
        try {
            d = prov.get(Provisioning.DomainBy.name, domain) ;
        }catch (Exception e) {
            //ignore
        }
        if (d == null) {
            errorMsg = "domain " + domain + " doesn't exist for account " + accountName ;
            throw ServiceException.PARSE_ERROR(errorMsg, new Exception(errorMsg)) ;
        }

        return true ;
    }

    //      private static char[] pwdChars = "abcdefghijklmnopqrstuvqxyzABCDEFGHIJKLMNOPQRSTUVWXYZ`1234567890~!@#$%^&*()".toCharArray();
    private static char[] pwdChars = "abcdefghijklmnopqrstuvqxyzABCDEFGHIJKLMNOPQRSTUVWXYZ1234567890".toCharArray();

    private static char[] generateStrongPassword(int length) {
        char[] pwd = new char[length];
        try {
          java.security.SecureRandom random = java.security.SecureRandom.
              getInstance("SHA1PRNG");

          byte[] intbytes = new byte[4];

          for (int i = 0; i < length; i++) {
            random.nextBytes(intbytes);
            pwd[i] = pwdChars[Math.abs(getIntFromByte(intbytes) % pwdChars.length)];
          }
        }
        catch (Exception ex) {
          // Don't really worry, we won't be using this if we can't use securerandom anyway
        }
        return pwd;
      }

      private static int getIntFromByte(byte[] bytes) {
        int returnNumber = 0;
        int pos = 0;
        returnNumber += byteToInt(bytes[pos++]) << 24;
        returnNumber += byteToInt(bytes[pos++]) << 16;
        returnNumber += byteToInt(bytes[pos++]) << 8;
        returnNumber += byteToInt(bytes[pos++]) << 0;
        return returnNumber;
      }

      private static int byteToInt(byte b) {
        return (int) b & 0xFF;
      }
    
    public static void main (String [] args) {
        try {
            System.out.println (generateStrongPassword(8));
        }catch (Exception e){
            e.printStackTrace();
        }
    }
}