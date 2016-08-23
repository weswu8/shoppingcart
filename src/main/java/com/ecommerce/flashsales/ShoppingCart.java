package com.ecommerce.flashsales;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.aspectj.weaver.ast.And;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.Version;

import net.rubyeye.xmemcached.MemcachedClient;
import net.rubyeye.xmemcached.exception.MemcachedException;

/***
 * 
 * @author wuwesley
 * The inventory management service for the whole system.
 */
@RestController
@RequestMapping("/")
public class  ShoppingCart {	
    /*** indicate current version of this micro service ***/
	public final String cVersion = "1.0";
	
	@Autowired
    private MemcachedClient memcachedClient;
    private final Logger logger = LoggerFactory.getLogger(this.getClass().getName());
    private String allItemsKeyPerUser = null;
    private final String xNameSpace = "ShoppingCart";
	FlashSalesAccessLogger fsAccessLogger = new FlashSalesAccessLogger();

	/*** rate limiter setting ***/
    @Value("${ratelimiter.consumeCount}")
	public double consumeCount;
    /***
     * Generate the md5 value for the pair of GoodsSku and Inventory no.
     * @param badguy
     * @return
     * @throws NoSuchAlgorithmException 
     */
    public String md5Hashing (String userID, String sku) throws NoSuchAlgorithmException{
		String md5String = null;
		String clientPair = null;
		
		clientPair = userID + ":" + sku + ":" + xNameSpace;
		
		MessageDigest md = MessageDigest.getInstance("MD5");
        md.update(clientPair.toString().getBytes());
        
        byte byteData[] = md.digest();
 
        //convert the byte to hex format method 1
        StringBuffer sb = new StringBuffer();
        for (int i = 0; i < byteData.length; i++) {
         sb.append(Integer.toString((byteData[i] & 0xff) + 0x100, 16).substring(1));
        }
        md5String = sb.toString();
		return md5String;
	}
    /***
	 * Create a new the goods's info within the user's cart
	 * Request url : http://localhost:8080/add/
	 * Request sample : {"sessionID":"113e5d875f81","userID":"FS000001","goodsSKU":"QT3456","goodsQuantity":1,"totalQuantity":100,"quantityLimit":0} 
	 * Response sample : {"sessionID":"113e5d875f81","userID":"FS000001","goodsSKU":"QT3456","goodsQuantity":1,"isAllowed":true,"isThrottled":false}
     * @throws ParseException 
     * @throws NoSuchAlgorithmException 
     * @throws JsonProcessingException 
	 */
	@SuppressWarnings("unchecked")
	@RequestMapping(method=RequestMethod.POST, value = "/add", headers = "Accept=application/json")
	public AddGoodsR doAddGoodsToCart(HttpServletRequest httpRequest, HttpServletResponse httpResponse, @RequestBody Goods goods) throws ParseException, NoSuchAlgorithmException, JsonProcessingException {
		int expirationValue = (int) (60*30);
		/*** initialize the result ***/
		AddGoodsR addGoodsR = new AddGoodsR();
		addGoodsR.setSessionID(goods.getSessionID());
		addGoodsR.setUserID(goods.getUserID());
		addGoodsR.setGoodsSKU(goods.getGoodsSKU());
		addGoodsR.setGoodsQuantity((int)goods.getGoodsQuantity());
		addGoodsR.setIslowed(false);
		addGoodsR.setIsThrottled(false);
		addGoodsR.setVersion(cVersion);
		
		/*** prepare the json string for the store ***/
		JSONObject jObj = new JSONObject();
		jObj.put("sessionID",goods.getSessionID());
		jObj.put("userID",goods.getUserID());
		jObj.put("goodsSKU",goods.getGoodsSKU());
		jObj.put("goodsQuantity",String.valueOf(goods.getGoodsQuantity()));
		jObj.put("totalQuantity",String.valueOf(goods.getTotalQuantity()));
		jObj.put("quantityLimit",String.valueOf(goods.getTotalQuantity()));
		
		/*** set the start time for performance logging ***/
		long startTime = System.currentTimeMillis();
		if (goods.getSessionID().length() > 0 && goods.getUserID().length() > 0 && goods.getGoodsSKU().length() > 0){
 			try {				
 				
 				/*** rate limiter checking ***/
 				if (ShoppingCartApplication.rateLimiter.consume(consumeCount) == false){
 					addGoodsR.setIsThrottled(true);
 					long endTime = System.currentTimeMillis();
 					fsAccessLogger.doAccessLog(httpRequest, httpResponse, goods.getSessionID(), CurrentStep.SHOPPINGCART.msgBody(), jObj.toString(), endTime-startTime, addGoodsR);
 					return addGoodsR;
 				}
 				
 				/*** check for the exist goods ***/
 				Goods existGoods = getGoodsByUidSKU(goods.getUserID(), goods.getGoodsSKU());
 				
 				/*** check the frozen item ***/
 				if (freezeGoodsBySKU(goods.getGoodsSKU(), goods.getGoodsQuantity(), goods.getTotalQuantity()) == false){
 					long endTime = System.currentTimeMillis();
 					fsAccessLogger.doAccessLog(httpRequest, httpResponse, goods.getSessionID(), CurrentStep.SHOPPINGCART.msgBody(), jObj.toString(), endTime-startTime, addGoodsR);
 					return addGoodsR;
 				}
 				/*** find the exist one, so we should check the policy again***/
 				if (existGoods.getGoodsSKU() != null){
					goods.setGoodsQuantity((existGoods.getGoodsQuantity()) + goods.getGoodsQuantity());
					addGoodsR.setGoodsQuantity(goods.getGoodsQuantity());
					/*** check the policy again, the zero value means no limit for this SKU ***/
					if (goods.getQuantityLimit() != 0 && addGoodsR.goodsQuantity > goods.getQuantityLimit()){
						long endTime = System.currentTimeMillis();
	 					fsAccessLogger.doAccessLog(httpRequest, httpResponse, goods.getSessionID(), CurrentStep.SHOPPINGCART.msgBody(), jObj.toString(), endTime-startTime, addGoodsR);
	 					return addGoodsR;
					}
					/*** pass the check ***/
					updateGoodsInCart(httpRequest, httpResponse, goods);
 				}else{ /*** not found the exist one ***/
 					memcachedClient.set(md5Hashing(goods.getUserID(), goods.getGoodsSKU()), expirationValue, jObj.toString());
 	 				allItemsKeyPerUser = md5Hashing(goods.getUserID(),"");
 	 				updateAllItemsKey(allItemsKeyPerUser, goods.getGoodsSKU(), "ADD");
 				}
 				addGoodsR.setIslowed(true);
			} catch (TimeoutException e) {
				logger.error("TimeoutException:"+ goods.getSessionID());
			} catch (InterruptedException e) {
				logger.error("InterruptedException:"+ goods.getSessionID());
			} catch (MemcachedException e) {
				logger.error("MemcachedException:"+ goods.getSessionID());
			}finally{
				long endTime = System.currentTimeMillis();
				fsAccessLogger.doAccessLog(httpRequest, httpResponse, goods.getSessionID(), CurrentStep.SHOPPINGCART.msgBody(), jObj.toString(), endTime-startTime, addGoodsR);
			}
 		}else{
 			long endTime = System.currentTimeMillis();
			fsAccessLogger.doAccessLog(httpRequest, httpResponse, goods.getSessionID(), CurrentStep.SHOPPINGCART.msgBody(), jObj.toString(), endTime-startTime, addGoodsR);
 		}
		return addGoodsR;
	}
	
	/***
	 * Get the goods's info from the user's cart
	 * Request sample : http://localhost:8080/sid/{sid}/userid/{userid}/sku/{sku}
	 * Response sample : {"sessionID":"113e5d875f81","userID":"FS000001","goodsSKU":"QT3456","goodsQuantity":1,"isAllowed":true,"isThrottled":false}
	 * @throws JsonProcessingException 
	 * @throws NoSuchAlgorithmException 
	 * @throws ParseException 
	 */
	@SuppressWarnings("unchecked")
	@RequestMapping(method = RequestMethod.GET, value = "/sid/{sid}/userid/{userid}/sku/{sku}")
	public AddGoodsR getGoodsByUidSKUService(HttpServletRequest httpRequest, HttpServletResponse httpResponse, @PathVariable("sid") String sid, @PathVariable("userid") String userid,  @PathVariable("sku") String sku) throws JsonProcessingException, NoSuchAlgorithmException, ParseException{
		/*** initialize the result ***/
		AddGoodsR addGoodsR = new AddGoodsR();
		addGoodsR.setSessionID(sid);
		addGoodsR.setUserID(userid);
		addGoodsR.setGoodsSKU(sku);
		addGoodsR.setGoodsQuantity(0);
		addGoodsR.setIslowed(false);
		addGoodsR.setIsThrottled(false);
		addGoodsR.setVersion(cVersion);
		
		/*** prepare the json string for the store ***/
		JSONObject jObj = new JSONObject();
		jObj.put("sessionID",addGoodsR.getSessionID());
		jObj.put("userID",addGoodsR.getUserID());
		jObj.put("goodsSKU",addGoodsR.getGoodsSKU());
		jObj.put("goodsQuantity",String.valueOf(addGoodsR.getGoodsQuantity()));
		
		/*** set the start time for performance logging ***/
		long startTime = System.currentTimeMillis();
		/*** rate limiter checking ***/
		if (ShoppingCartApplication.rateLimiter.consume(consumeCount) == false){
			addGoodsR.setIsThrottled(true);
			long endTime = System.currentTimeMillis();
			fsAccessLogger.doAccessLog(httpRequest, httpResponse, addGoodsR.getSessionID(), CurrentStep.SHOPPINGCART.msgBody(), jObj.toString(), endTime-startTime, addGoodsR);
			return addGoodsR;
		}
		/*** check for the exist goods ***/
		Goods existGoods = getGoodsByUidSKU(addGoodsR.getUserID(), addGoodsR.getGoodsSKU());
		/*** update the result ***/
		addGoodsR.setGoodsQuantity(existGoods.goodsQuantity);
		/*** return the result ***/
		return addGoodsR;
	}
	/***
	 * 
	 * @param userid
	 * @param sku
	 * @return goods.class
	 * @throws NoSuchAlgorithmException
	 * @throws ParseException
	 */
	public Goods getGoodsByUidSKU(String userid,String sku) throws NoSuchAlgorithmException, ParseException {
		Object mObject = null;
		Goods goods = new Goods() ;
		if (userid.length() > 0 && sku.length() > 0){
 			try {
 				mObject = memcachedClient.get(md5Hashing((String)userid,(String)sku));
 				if (mObject != null){
 					JSONParser parser = new JSONParser();
 					JSONObject json = (JSONObject) parser.parse(mObject.toString());
 					if (json.get("sessionID") != null) {goods.setSessionID(json.get("sessionID").toString());}
 					goods.setUserID(json.get("userID").toString());
 					goods.setGoodsSKU(json.get("goodsSKU").toString());
 					goods.setGoodsQuantity(Integer.parseInt(json.get("goodsQuantity").toString()));
 				}				
			} catch (TimeoutException e) {
				logger.error("TimeoutException");
			} catch (InterruptedException e) {
				logger.error("InterruptedException");
			} catch (MemcachedException e) {
				logger.error("MemcachedException");
			}
 		}
		return goods;
	}
	/***
	 * update the goods's  info from the user's cart, this function does not yet linked with inventory freeze and release operation (30/7).
	 * Request sample : http://localhost:8080/update
	 * Request sample : {"sessionID":"113e5d875f81","userID":"FS000001","goodsSKU":"QT3456","goodsQuantity":1,"totalQuantity":100} 
	 * Response sample : {"sessionID":"113e5d875f81","userID":"FS000001","goodsSKU":"QT3456","goodsQuantity":1,"isAllowed":true,"isThrottled":false}
   	 * @throws NoSuchAlgorithmException 
	 * @throws ParseException 
	 * @throws JsonProcessingException 
	 */
	@SuppressWarnings("unchecked")
	@RequestMapping(method = RequestMethod.PUT, value = "/update", headers = "Accept=application/json")	
	public AddGoodsR updateGoodsInCart(HttpServletRequest httpRequest, HttpServletResponse httpResponse, @RequestBody Goods goods) throws NoSuchAlgorithmException, JsonProcessingException, ParseException {
		int expirationValue = (int) (60*30);
		/*** initialize the result ***/
		AddGoodsR addGoodsR = new AddGoodsR();
		addGoodsR.setSessionID(goods.getSessionID());
		addGoodsR.setUserID(goods.getUserID());
		addGoodsR.setGoodsSKU(goods.getGoodsSKU());
		addGoodsR.setGoodsQuantity(goods.getGoodsQuantity());
		addGoodsR.setIslowed(false);
		addGoodsR.setVersion(cVersion);
		
		JSONObject jObj = new JSONObject();
		if (goods.getGoodsSKU().length() > 0 && goods.getGoodsSKU() != null){
 			try { 				
 				jObj.put("sessionID",goods.getSessionID());
 				jObj.put("userID",goods.getUserID());
 				jObj.put("goodsSKU",goods.getGoodsSKU());
 				jObj.put("goodsQuantity",goods.getGoodsQuantity());
 				memcachedClient.replace(md5Hashing(goods.getUserID(), goods.getGoodsSKU()), expirationValue, jObj.toString());
 				addGoodsR.setIslowed(true);
			} catch (TimeoutException e) {
				logger.error("TimeoutException");
			} catch (InterruptedException e) {
				logger.error("InterruptedException");
			} catch (MemcachedException e) {
				logger.error("MemcachedException");
			}
 		}
		return addGoodsR;
	}
	/***
	 * Delete the goods's from the user's cart
	 * Request sample : http://localhost:8080/delete/userid/{userid}/sku/{sku}
	 * Response sample : [{goods01},{goods012}]
	 * @throws ParseException 
	 * @throws NoSuchAlgorithmException 
	 */
	@RequestMapping(method=RequestMethod.DELETE, value = "/delete/userid/{userid}/sku/{sku}")
	public List<Goods> removeGoodsInCart(@PathVariable("userid") String userid,@PathVariable("sku") String sku) throws NoSuchAlgorithmException, ParseException{
		removeGoodsByUidSku(userid, sku);
		return findAllItemsByUserID(userid);
	}
	public boolean removeGoodsByUidSku(String userid, String sku) throws ParseException, NoSuchAlgorithmException {
		boolean successed = false;
		Goods goods = new Goods();
		if (userid.length() > 0 && sku.length() > 0){
 			try {
				/*** get the quantity, this will be released for the pool ***/
 				goods = getGoodsByUidSKU(userid, sku);

 				memcachedClient.delete(md5Hashing(userid, sku));
				allItemsKeyPerUser = md5Hashing(userid,"");
 				updateAllItemsKey(allItemsKeyPerUser, sku, "DELETE");
 				
 				/*** release the number ***/
 				if (goods.goodsQuantity > 0){
 					releaseGoodsBySKU(sku, goods.goodsQuantity);
 				}
				successed = true;
			} catch (TimeoutException e) {
				logger.error("TimeoutException");
			} catch (InterruptedException e) {
				logger.error("InterruptedException");
			} catch (MemcachedException e) {
				logger.error("MemcachedException");
			}
 		}
		return successed;
	}
	/***
	 * find all items for the user
	 * Request sample : http://localhost:8080/all/userid/{userid}
	 * Response sample : [{goods},{goods},{goods}}
	 * @throws ParseException 
	 * @throws NoSuchAlgorithmException 
	 */
	@CrossOrigin
	@RequestMapping(method = RequestMethod.GET, value = "/all/userid/{userid}")
	public List<Goods> findAllItemsByUserID(@PathVariable("userid") String userid) throws NoSuchAlgorithmException, ParseException{
		List<Goods> glist = new ArrayList<>();
		List<String> mlist = null;
		Object mObject = null;
		allItemsKeyPerUser = md5Hashing(userid,"");
		try {
			mObject = memcachedClient.get(allItemsKeyPerUser);
			if (mObject != null){
				mlist = new ArrayList<String>(Arrays.asList(mObject.toString().split(",")));
				for(String mSku:mlist){
					if (mSku.trim().length() > 0) {glist.add(getGoodsByUidSKU(userid,mSku));}
				}
			}else{
				glist.add(new Goods());
			}
		} catch (TimeoutException e) {
			logger.error("TimeoutException");
		} catch (InterruptedException e) {
			logger.error("InterruptedException");
		} catch (MemcachedException e) {
			logger.error("MemcachedException");
		}
		return glist;
		
	}
	/***
	 * store the key index for one user
	 * allItemsKeyPerUser(xAllItemsKey):xxx,xxxx,xxxx
	 * @throws ParseException 
	 */
	public void updateAllItemsKey(String allItemsKeyPerUser,String theItemKey,String mode) throws ParseException {
		Object mObject = null;
		List<String> mlist = null;
		String tmpItemsKey = null;
		/*** set the expiration time: 30 minutes ***/
		int expirationValue = (int) (60*30);
		try {
			mObject = memcachedClient.get(allItemsKeyPerUser);
			if (mObject != null){
				mlist = new ArrayList<String>(Arrays.asList(mObject.toString().split(",")));
				if (mode == "ADD"){
					//avoid the duplicated key issue
					if (mlist.contains(theItemKey) == false){mlist.add(theItemKey);}
				}else{
					mlist.remove(theItemKey);
				}
				tmpItemsKey = mlist.toString().replace("[", "").replace("]", "").replace(" ", "").trim() + ",";
				memcachedClient.replace(allItemsKeyPerUser, expirationValue, tmpItemsKey);
			}else{
				tmpItemsKey = theItemKey + ",";
				memcachedClient.add(allItemsKeyPerUser, expirationValue, tmpItemsKey);
			}
		} catch (TimeoutException e) {
			logger.error("TimeoutException");
		} catch (InterruptedException e) {
			logger.error("InterruptedException");
		} catch (MemcachedException e) {
			logger.error("MemcachedException");
		}
	}
	/***
	 * to avoid the over sale, will freeze the numbers of the goods
	 * Request sample : http://localhost:8080/freeze/sku/{sku}/buyqty/{buyqty}
	 * Response sample : [{goods},{goods},{goods}}
	 */
	@SuppressWarnings({ "rawtypes", "unchecked" })
	@RequestMapping(method = RequestMethod.GET, value = "/freeze/sku/{sku}/buyqty/{buyqty}/totalqty/{totalqty}")
	public Boolean freezeGoodsBySKU(@PathVariable("sku") String sku, @PathVariable("buyqty") int buyqty, @PathVariable("totalqty") int totalqty) throws NoSuchAlgorithmException, TimeoutException, InterruptedException, MemcachedException, ParseException{
		Boolean freezeRes = false;
		Object mObject = null;
		/*** set the log expiration time for the item key ***/
		long timeSeconds = TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis());
		int expirationValue = (int) (timeSeconds + 24*60*60*365);
		
		/*** the time slot is 10 seconds, so there are 30*6 slots with in 30 minutes; ***/
		long currentSlot = System.currentTimeMillis()/(1000*10);
		long oldestKey = currentSlot - 30*6;/*** Calculate the time point for previous 30 minutes ***/
		long tmpKey = 0; /*** tract the point within loop ***/
		boolean dupKey = false; /*** whether the slot point exist or not ***/
		int frozenQty = 0; /*** the quantity already been frozen ***/
		int releasedQty = 0; /*** the quantity already been released ***/
		ArrayList<String> removedKey = new ArrayList<String>();
		mObject = memcachedClient.get(md5Hashing("freeze",sku));
 		/*** if does not find the item, add it ***/
		if (mObject == null){
			JSONObject mJson = new JSONObject();
	        mJson.put(currentSlot, buyqty);
			memcachedClient.set(md5Hashing("freeze",sku), expirationValue, mJson.toString());
			/*** if the buyqty is smaller than the totalqty, should return success ***/
			if (totalqty >= buyqty){ freezeRes = true; }
		}else{ /*** find the exist item key ,we should handle it ***/
			JSONParser parser = new JSONParser();
			JSONObject tJson = (JSONObject) parser.parse(mObject.toString());
			for(Iterator iterator = tJson.keySet().iterator(); iterator.hasNext();) {
				String slotIte = (String) iterator.next();
				tmpKey = Long.parseLong(slotIte.toString());			  
			    /*** if the currentSlot exist already ***/
			    if (currentSlot == tmpKey){
			    	int lastQuantity = buyqty + Integer.parseInt(tJson.get(slotIte).toString());
			    	tJson.replace(slotIte, String.valueOf(lastQuantity));
			    	dupKey = true;
			    }
			    /*** delete the older key ***/
			    if (tmpKey < oldestKey){
			    	removedKey.add(slotIte);
			    }
			   
			    /*** sum the total locked number ***/
			    if (tJson.containsKey(slotIte)){
			    	frozenQty += Integer.parseInt(tJson.get(slotIte).toString());
			    }

			}
			/*** remove the oldest key ***/
			for (String rkey : removedKey) {
				tJson.remove(rkey);
			}

			/*** for the item, the key of currentSlot does not exist ,we should add it ***/
			if (dupKey == false){
				tJson.put(currentSlot, buyqty);
				frozenQty += buyqty;
			}
			
			/*** get the released numbers of the goods ***/
			releasedQty = getReleasedGoodsBySKU(sku);
			if (totalqty >= (frozenQty - releasedQty)){
				/*** save the value to cache ***/
				memcachedClient.replace(md5Hashing("freeze",sku), expirationValue, tJson.toString());	
				freezeRes = true;
			}
		}	    
		return freezeRes;
	}
	/***
	 * when goods is deleted by user, will release the numbers of the goods
	 * Request sample : http://localhost:8080/release/sku/{sku}/buyqty/{buyqty}
	 * Response sample : [{goods},{goods},{goods}}
	 */
	@SuppressWarnings({ "rawtypes", "unchecked" })
	@RequestMapping(method = RequestMethod.GET, value = "/release/sku/{sku}/buyqty/{buyqty}")
	public Boolean releaseGoodsBySKU(@PathVariable("sku") String sku, @PathVariable("buyqty") int buyqty) throws NoSuchAlgorithmException, TimeoutException, InterruptedException, MemcachedException, ParseException{
		Boolean releaseRes = false;
		Object mObject = null;
		/*** set the log expiration time for the item key ***/
		long timeSeconds = TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis());
		int expirationValue = (int) (timeSeconds + 24*60*60*365);
		
		/*** the time slot is 10 seconds, so there are 30*6 slots with in 30 minutes; ***/
		long currentSlot = System.currentTimeMillis()/(1000*10);
		long oldestKey = currentSlot - 30*6;/*** Calculate the time point for previous 30 minutes ***/
		long tmpKey = 0; /*** tract the point within loop ***/
		boolean dupKey = false; /*** whether the slot point exist or not ***/
		ArrayList<String> removedKey = new ArrayList<String>();
		mObject = memcachedClient.get(md5Hashing("release",sku));
 		/*** if does not find the item, add it ***/
		if (mObject == null){
			JSONObject mJson = new JSONObject();
	        mJson.put(currentSlot, buyqty);
			memcachedClient.set(md5Hashing("release",sku), expirationValue, mJson.toString());
			releaseRes = true;
		}else{ /*** find the exist item key ,we should handle it ***/
			JSONParser parser = new JSONParser();
			JSONObject tJson = (JSONObject) parser.parse(mObject.toString());
			for(Iterator iterator = tJson.keySet().iterator(); iterator.hasNext();) {
				String slotIte = (String) iterator.next();
				tmpKey = Long.parseLong(slotIte.toString());			  
			    /*** if the currentSlot exist already ***/
			    if (currentSlot == tmpKey){
			    	int lastQuantity = buyqty + Integer.parseInt(tJson.get(slotIte).toString());
			    	tJson.replace(slotIte, String.valueOf(lastQuantity));
			    	dupKey = true;
			    }
			    /*** delete the older key ***/
			    if (tmpKey < oldestKey){
			    	removedKey.add(slotIte);
			    }	    

			}
			/*** remove the oldest key ***/
			for (String rkey : removedKey) {
				tJson.remove(rkey);
			}

			/*** for the item, the key of currentSlot does not exist ,we should add it ***/
			if (dupKey == false){
				tJson.put(currentSlot, buyqty);
			}
			
			/*** save the value to cache ***/
			memcachedClient.replace(md5Hashing("release",sku), expirationValue, tJson.toString());	
			releaseRes = true;
		}	    
		return releaseRes;
	}
	/***
	 * get released number of the goods, this will be judged when adding goods to car.
	 * Response sample : xxx
	 */
	@SuppressWarnings({ "rawtypes" })
	public int getReleasedGoodsBySKU(String sku) throws NoSuchAlgorithmException, TimeoutException, InterruptedException, MemcachedException, ParseException{
		int releasedQty = 0; /*** the quantity already been released ***/
		Object mObject = null;

		/*** the time slot is 10 seconds, so there are 30*6 slots with in 30 minutes; ***/
		long currentSlot = System.currentTimeMillis()/(1000*10);
		long oldestKey = currentSlot - 30*6;/*** Calculate the time point for previous 30 minutes ***/
		long tmpKey = 0; /*** tract the point within loop ***/

		mObject = memcachedClient.get(md5Hashing("release",sku));
 		/*** if find the item ,sum it's values ***/
		if (mObject != null){
			JSONParser parser = new JSONParser();
			JSONObject tJson = (JSONObject) parser.parse(mObject.toString());
			for(Iterator iterator = tJson.keySet().iterator(); iterator.hasNext();) {
				String slotIte = (String) iterator.next();
				tmpKey = Long.parseLong(slotIte.toString());			  

			    /*** sum the total release number within the active period ***/
			    if (tmpKey > oldestKey){
			    	if (tJson.containsKey(slotIte)){
				    	releasedQty += Integer.parseInt(tJson.get(slotIte).toString());
				    }
			    }
			}/*** end of for ***/

		}/*** end of if ***/	    
		return releasedQty;
	}
}
