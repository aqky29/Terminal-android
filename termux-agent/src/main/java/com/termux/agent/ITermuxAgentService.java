package com.termux.agent;

import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;

public interface ITermuxAgentService extends IInterface {

    int getUid() throws RemoteException;

    int getPid() throws RemoteException;

    String getSeContext() throws RemoteException;

    Bundle execCommand(String command, String cwd, long timeoutMs) throws RemoteException;

    int execStreaming(String command, String cwd, long timeoutMs, ParcelFileDescriptor stdoutFd, ParcelFileDescriptor stderrFd) throws RemoteException;

    Bundle getDeviceSnapshot() throws RemoteException;

    void exit() throws RemoteException;

    abstract class Stub extends Binder implements ITermuxAgentService {
        private static final String DESCRIPTOR = "com.termux.agent.ITermuxAgentService";

        static final int TRANSACTION_getUid = IBinder.FIRST_CALL_TRANSACTION;
        static final int TRANSACTION_getPid = IBinder.FIRST_CALL_TRANSACTION + 1;
        static final int TRANSACTION_getSeContext = IBinder.FIRST_CALL_TRANSACTION + 2;
        static final int TRANSACTION_execCommand = IBinder.FIRST_CALL_TRANSACTION + 3;
        static final int TRANSACTION_execStreaming = IBinder.FIRST_CALL_TRANSACTION + 4;
        static final int TRANSACTION_getDeviceSnapshot = IBinder.FIRST_CALL_TRANSACTION + 5;
        static final int TRANSACTION_exit = IBinder.FIRST_CALL_TRANSACTION + 6;

        public Stub() {
            attachInterface(this, DESCRIPTOR);
        }

        public static ITermuxAgentService asInterface(IBinder obj) {
            if (obj == null) return null;
            IInterface iin = obj.queryLocalInterface(DESCRIPTOR);
            if (iin instanceof ITermuxAgentService) {
                return (ITermuxAgentService) iin;
            }
            return new Proxy(obj);
        }

        @Override
        public IBinder asBinder() {
            return this;
        }

        @Override
        public boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            if (code == INTERFACE_TRANSACTION) {
                reply.writeString(DESCRIPTOR);
                return true;
            }
            switch (code) {
                case TRANSACTION_getUid: {
                    data.enforceInterface(DESCRIPTOR);
                    int res = this.getUid();
                    reply.writeNoException();
                    reply.writeInt(res);
                    return true;
                }
                case TRANSACTION_getPid: {
                    data.enforceInterface(DESCRIPTOR);
                    int res = this.getPid();
                    reply.writeNoException();
                    reply.writeInt(res);
                    return true;
                }
                case TRANSACTION_getSeContext: {
                    data.enforceInterface(DESCRIPTOR);
                    String res = this.getSeContext();
                    reply.writeNoException();
                    reply.writeString(res);
                    return true;
                }
                case TRANSACTION_execCommand: {
                    data.enforceInterface(DESCRIPTOR);
                    String command = data.readString();
                    String cwd = data.readString();
                    long timeoutMs = data.readLong();
                    Bundle res = this.execCommand(command, cwd, timeoutMs);
                    reply.writeNoException();
                    if (res != null) {
                        reply.writeInt(1);
                        res.writeToParcel(reply, android.os.Parcelable.PARCELABLE_WRITE_RETURN_VALUE);
                    } else {
                        reply.writeInt(0);
                    }
                    return true;
                }
                case TRANSACTION_execStreaming: {
                    data.enforceInterface(DESCRIPTOR);
                    String command = data.readString();
                    String cwd = data.readString();
                    long timeoutMs = data.readLong();
                    ParcelFileDescriptor stdoutFd = (data.readInt() != 0)
                        ? ParcelFileDescriptor.CREATOR.createFromParcel(data) : null;
                    ParcelFileDescriptor stderrFd = (data.readInt() != 0)
                        ? ParcelFileDescriptor.CREATOR.createFromParcel(data) : null;
                    int res = this.execStreaming(command, cwd, timeoutMs, stdoutFd, stderrFd);
                    reply.writeNoException();
                    reply.writeInt(res);
                    return true;
                }
                case TRANSACTION_getDeviceSnapshot: {
                    data.enforceInterface(DESCRIPTOR);
                    Bundle res = this.getDeviceSnapshot();
                    reply.writeNoException();
                    if (res != null) {
                        reply.writeInt(1);
                        res.writeToParcel(reply, android.os.Parcelable.PARCELABLE_WRITE_RETURN_VALUE);
                    } else {
                        reply.writeInt(0);
                    }
                    return true;
                }
                case TRANSACTION_exit: {
                    data.enforceInterface(DESCRIPTOR);
                    this.exit();
                    reply.writeNoException();
                    return true;
                }
                default:
                    return super.onTransact(code, data, reply, flags);
            }
        }

        private static class Proxy implements ITermuxAgentService {
            private final IBinder mRemote;

            Proxy(IBinder remote) {
                mRemote = remote;
            }

            @Override
            public IBinder asBinder() {
                return mRemote;
            }

            @Override
            public int getUid() throws RemoteException {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    mRemote.transact(TRANSACTION_getUid, data, reply, 0);
                    reply.readException();
                    return reply.readInt();
                } finally {
                    reply.recycle();
                    data.recycle();
                }
            }

            @Override
            public int getPid() throws RemoteException {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    mRemote.transact(TRANSACTION_getPid, data, reply, 0);
                    reply.readException();
                    return reply.readInt();
                } finally {
                    reply.recycle();
                    data.recycle();
                }
            }

            @Override
            public String getSeContext() throws RemoteException {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    mRemote.transact(TRANSACTION_getSeContext, data, reply, 0);
                    reply.readException();
                    return reply.readString();
                } finally {
                    reply.recycle();
                    data.recycle();
                }
            }

            @Override
            public Bundle execCommand(String command, String cwd, long timeoutMs) throws RemoteException {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    data.writeString(command);
                    data.writeString(cwd);
                    data.writeLong(timeoutMs);
                    mRemote.transact(TRANSACTION_execCommand, data, reply, 0);
                    reply.readException();
                    return (reply.readInt() != 0) ? Bundle.CREATOR.createFromParcel(reply) : null;
                } finally {
                    reply.recycle();
                    data.recycle();
                }
            }

            @Override
            public int execStreaming(String command, String cwd, long timeoutMs, ParcelFileDescriptor stdoutFd, ParcelFileDescriptor stderrFd) throws RemoteException {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    data.writeString(command);
                    data.writeString(cwd);
                    data.writeLong(timeoutMs);
                    if (stdoutFd != null) {
                        data.writeInt(1);
                        stdoutFd.writeToParcel(data, 0);
                    } else {
                        data.writeInt(0);
                    }
                    if (stderrFd != null) {
                        data.writeInt(1);
                        stderrFd.writeToParcel(data, 0);
                    } else {
                        data.writeInt(0);
                    }
                    mRemote.transact(TRANSACTION_execStreaming, data, reply, 0);
                    reply.readException();
                    return reply.readInt();
                } finally {
                    reply.recycle();
                    data.recycle();
                }
            }

            @Override
            public Bundle getDeviceSnapshot() throws RemoteException {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    mRemote.transact(TRANSACTION_getDeviceSnapshot, data, reply, 0);
                    reply.readException();
                    return (reply.readInt() != 0) ? Bundle.CREATOR.createFromParcel(reply) : null;
                } finally {
                    reply.recycle();
                    data.recycle();
                }
            }

            @Override
            public void exit() throws RemoteException {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    mRemote.transact(TRANSACTION_exit, data, reply, 0);
                    reply.readException();
                } finally {
                    reply.recycle();
                    data.recycle();
                }
            }
        }
    }
}
