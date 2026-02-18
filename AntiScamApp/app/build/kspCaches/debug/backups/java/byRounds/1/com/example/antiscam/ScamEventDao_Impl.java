package com.example.antiscam;

import android.database.Cursor;
import androidx.annotation.NonNull;
import androidx.room.EntityInsertionAdapter;
import androidx.room.RoomDatabase;
import androidx.room.RoomSQLiteQuery;
import androidx.room.SharedSQLiteStatement;
import androidx.room.util.CursorUtil;
import androidx.room.util.DBUtil;
import androidx.sqlite.db.SupportSQLiteStatement;
import java.lang.Class;
import java.lang.Double;
import java.lang.Override;
import java.lang.String;
import java.lang.SuppressWarnings;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.annotation.processing.Generated;

@Generated("androidx.room.RoomProcessor")
@SuppressWarnings({"unchecked", "deprecation"})
public final class ScamEventDao_Impl implements ScamEventDao {
  private final RoomDatabase __db;

  private final EntityInsertionAdapter<ScamEvent> __insertionAdapterOfScamEvent;

  private final SharedSQLiteStatement __preparedStmtOfClearAll;

  public ScamEventDao_Impl(@NonNull final RoomDatabase __db) {
    this.__db = __db;
    this.__insertionAdapterOfScamEvent = new EntityInsertionAdapter<ScamEvent>(__db) {
      @Override
      @NonNull
      protected String createQuery() {
        return "INSERT OR ABORT INTO `scam_events` (`id`,`timestamp`,`packageName`,`appName`,`riskScore`,`textSnippet`,`action`) VALUES (nullif(?, 0),?,?,?,?,?,?)";
      }

      @Override
      protected void bind(@NonNull final SupportSQLiteStatement statement,
          @NonNull final ScamEvent entity) {
        statement.bindLong(1, entity.getId());
        statement.bindLong(2, entity.getTimestamp());
        statement.bindString(3, entity.getPackageName());
        statement.bindString(4, entity.getAppName());
        statement.bindDouble(5, entity.getRiskScore());
        statement.bindString(6, entity.getTextSnippet());
        statement.bindString(7, entity.getAction());
      }
    };
    this.__preparedStmtOfClearAll = new SharedSQLiteStatement(__db) {
      @Override
      @NonNull
      public String createQuery() {
        final String _query = "DELETE FROM scam_events";
        return _query;
      }
    };
  }

  @Override
  public void insert(final ScamEvent event) {
    __db.assertNotSuspendingTransaction();
    __db.beginTransaction();
    try {
      __insertionAdapterOfScamEvent.insert(event);
      __db.setTransactionSuccessful();
    } finally {
      __db.endTransaction();
    }
  }

  @Override
  public void clearAll() {
    __db.assertNotSuspendingTransaction();
    final SupportSQLiteStatement _stmt = __preparedStmtOfClearAll.acquire();
    try {
      __db.beginTransaction();
      try {
        _stmt.executeUpdateDelete();
        __db.setTransactionSuccessful();
      } finally {
        __db.endTransaction();
      }
    } finally {
      __preparedStmtOfClearAll.release(_stmt);
    }
  }

  @Override
  public List<ScamEvent> getAll() {
    final String _sql = "SELECT * FROM scam_events ORDER BY timestamp DESC";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 0);
    __db.assertNotSuspendingTransaction();
    final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
    try {
      final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
      final int _cursorIndexOfTimestamp = CursorUtil.getColumnIndexOrThrow(_cursor, "timestamp");
      final int _cursorIndexOfPackageName = CursorUtil.getColumnIndexOrThrow(_cursor, "packageName");
      final int _cursorIndexOfAppName = CursorUtil.getColumnIndexOrThrow(_cursor, "appName");
      final int _cursorIndexOfRiskScore = CursorUtil.getColumnIndexOrThrow(_cursor, "riskScore");
      final int _cursorIndexOfTextSnippet = CursorUtil.getColumnIndexOrThrow(_cursor, "textSnippet");
      final int _cursorIndexOfAction = CursorUtil.getColumnIndexOrThrow(_cursor, "action");
      final List<ScamEvent> _result = new ArrayList<ScamEvent>(_cursor.getCount());
      while (_cursor.moveToNext()) {
        final ScamEvent _item;
        final int _tmpId;
        _tmpId = _cursor.getInt(_cursorIndexOfId);
        final long _tmpTimestamp;
        _tmpTimestamp = _cursor.getLong(_cursorIndexOfTimestamp);
        final String _tmpPackageName;
        _tmpPackageName = _cursor.getString(_cursorIndexOfPackageName);
        final String _tmpAppName;
        _tmpAppName = _cursor.getString(_cursorIndexOfAppName);
        final double _tmpRiskScore;
        _tmpRiskScore = _cursor.getDouble(_cursorIndexOfRiskScore);
        final String _tmpTextSnippet;
        _tmpTextSnippet = _cursor.getString(_cursorIndexOfTextSnippet);
        final String _tmpAction;
        _tmpAction = _cursor.getString(_cursorIndexOfAction);
        _item = new ScamEvent(_tmpId,_tmpTimestamp,_tmpPackageName,_tmpAppName,_tmpRiskScore,_tmpTextSnippet,_tmpAction);
        _result.add(_item);
      }
      return _result;
    } finally {
      _cursor.close();
      _statement.release();
    }
  }

  @Override
  public int getTotalCount() {
    final String _sql = "SELECT COUNT(*) FROM scam_events";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 0);
    __db.assertNotSuspendingTransaction();
    final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
    try {
      final int _result;
      if (_cursor.moveToFirst()) {
        _result = _cursor.getInt(0);
      } else {
        _result = 0;
      }
      return _result;
    } finally {
      _cursor.close();
      _statement.release();
    }
  }

  @Override
  public int getBlockedCount() {
    final String _sql = "SELECT COUNT(*) FROM scam_events WHERE action = 'BLOCKED'";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 0);
    __db.assertNotSuspendingTransaction();
    final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
    try {
      final int _result;
      if (_cursor.moveToFirst()) {
        _result = _cursor.getInt(0);
      } else {
        _result = 0;
      }
      return _result;
    } finally {
      _cursor.close();
      _statement.release();
    }
  }

  @Override
  public Double getAverageRisk() {
    final String _sql = "SELECT AVG(riskScore) FROM scam_events";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 0);
    __db.assertNotSuspendingTransaction();
    final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
    try {
      final Double _result;
      if (_cursor.moveToFirst()) {
        final Double _tmp;
        if (_cursor.isNull(0)) {
          _tmp = null;
        } else {
          _tmp = _cursor.getDouble(0);
        }
        _result = _tmp;
      } else {
        _result = null;
      }
      return _result;
    } finally {
      _cursor.close();
      _statement.release();
    }
  }

  @NonNull
  public static List<Class<?>> getRequiredConverters() {
    return Collections.emptyList();
  }
}
