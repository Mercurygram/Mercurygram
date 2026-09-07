package it.belloworld.mercurygram;

import org.telegram.messenger.MessageObject;
import org.telegram.tgnet.TLRPC;

public final class MgIncomingLiveLocation {

	public final int account;
	public final long dialogId;
	public final TLRPC.Message message;
	public final long senderId;

	public MgIncomingLiveLocation(int account, long dialogId, TLRPC.Message message) {
		this.account = account;
		this.dialogId = dialogId;
		this.message = message;
		this.senderId = MessageObject.getFromChatId(message);
	}

	public int getMessageId() {
		return message.id;
	}
}
