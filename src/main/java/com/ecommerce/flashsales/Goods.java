package com.ecommerce.flashsales;
/***
 * 
 * @author wuwesley
 * the class for good inventory info
 */

public class Goods {
	public String sessionID;
	public String userID;
	public String goodsSKU;
	public int goodsQuantity;
	public int totalQuantity;
	public int quantityLimit;
	
	public String getSessionID() {
		return sessionID;
	}
	public void setSessionID(String sessionID) {
		this.sessionID = sessionID;
	}
	public String getUserID() {
		return userID;
	}
	public void setUserID(String userID) {
		this.userID = userID;
	}
	public String getGoodsSKU() {
		return goodsSKU;
	}
	public void setGoodsSKU(String goodsSKU) {
		this.goodsSKU = goodsSKU;
	}
	public int getGoodsQuantity() {
		return goodsQuantity;
	}
	public void setGoodsQuantity(int goodsQuantity) {
		this.goodsQuantity = goodsQuantity;
	}

	public int getTotalQuantity() {
		return totalQuantity;
	}
	public void setTotalQuantity(int totalQuantity) {
		this.totalQuantity = totalQuantity;
	}
	public int getQuantityLimit() {
		return quantityLimit;
	}
	public void setQuantityLimit(int quantityLimit) {
		this.quantityLimit = quantityLimit;
	}
	@Override
	public String toString() {
		return "Goods [sessionID=" + sessionID + ", userID=" + userID + ", goodsSKU=" + goodsSKU + ", goodsQuantity="
				+ goodsQuantity + ", totalQuantity=" + totalQuantity + ", quantityLimit=" + quantityLimit + "]";
	}	
	
}
