/*
 * Copyright 2004-2014 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package com.suning.snfddal.command.expression;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;

import com.suning.snfddal.command.Parser;
import com.suning.snfddal.command.dml.Select;
import com.suning.snfddal.dbobject.Aggregate;
import com.suning.snfddal.dbobject.UserAggregate;
import com.suning.snfddal.dbobject.table.ColumnResolver;
import com.suning.snfddal.dbobject.table.TableFilter;
import com.suning.snfddal.engine.Session;
import com.suning.snfddal.message.DbException;
import com.suning.snfddal.message.ErrorCode;
import com.suning.snfddal.util.StatementBuilder;
import com.suning.snfddal.value.DataType;
import com.suning.snfddal.value.Value;
import com.suning.snfddal.value.ValueNull;

/**
 * This class wraps a user-defined aggregate.
 */
public class JavaAggregate extends Expression {

    private final UserAggregate userAggregate;
    private final Select select;
    private final Expression[] args;
    private int[] argTypes;
    private int dataType;
    private Connection userConnection;
    private int lastGroupRowId;

    public JavaAggregate(UserAggregate userAggregate, Expression[] args,
            Select select) {
        this.userAggregate = userAggregate;
        this.args = args;
        this.select = select;
    }

    @Override
    public int getCost() {
        int cost = 5;
        for (Expression e : args) {
            cost += e.getCost();
        }
        return cost;
    }

    @Override
    public long getPrecision() {
        return Integer.MAX_VALUE;
    }

    @Override
    public int getDisplaySize() {
        return Integer.MAX_VALUE;
    }

    @Override
    public int getScale() {
        return DataType.getDataType(dataType).defaultScale;
    }

    @Override
    public String getSQL() {
        StatementBuilder buff = new StatementBuilder();
        buff.append(Parser.quoteIdentifier(userAggregate.getName())).append('(');
        for (Expression e : args) {
            buff.appendExceptFirst(", ");
            buff.append(e.getSQL());
        }
        return buff.append(')').toString();
    }

    @Override
    public int getType() {
        return dataType;
    }

    @Override
    public boolean isEverything(ExpressionVisitor visitor) {
        switch(visitor.getType()) {
        case ExpressionVisitor.DETERMINISTIC:
            // TODO optimization: some functions are deterministic, but we don't
            // know (no setting for that)
        case ExpressionVisitor.OPTIMIZABLE_MIN_MAX_COUNT_ALL:
            // user defined aggregate functions can not be optimized
            return false;
        case ExpressionVisitor.GET_DEPENDENCIES:
            visitor.addDependency(userAggregate);
            break;
        default:
        }
        for (Expression e : args) {
            if (e != null && !e.isEverything(visitor)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void mapColumns(ColumnResolver resolver, int level) {
        for (Expression arg : args) {
            arg.mapColumns(resolver, level);
        }
    }

    @Override
    public Expression optimize(Session session) {
        userConnection = session.createConnection(false);
        int len = args.length;
        argTypes = new int[len];
        for (int i = 0; i < len; i++) {
            Expression expr = args[i];
            args[i] = expr.optimize(session);
            int type = expr.getType();
            argTypes[i] = type;
        }
        try {
            Aggregate aggregate = getInstance();
            dataType = aggregate.getInternalType(argTypes);
        } catch (SQLException e) {
            throw DbException.convert(e);
        }
        return this;
    }

    @Override
    public void setEvaluatable(TableFilter tableFilter, boolean b) {
        for (Expression e : args) {
            e.setEvaluatable(tableFilter, b);
        }
    }

    private Aggregate getInstance() throws SQLException {
        Aggregate agg = userAggregate.getInstance();
        agg.init(userConnection);
        return agg;
    }

    @Override
    public Value getValue(Session session) {
        HashMap<Expression, Object> group = select.getCurrentGroup();
        if (group == null) {
            throw DbException.get(ErrorCode.INVALID_USE_OF_AGGREGATE_FUNCTION_1, getSQL());
        }
        try {
            Aggregate agg = (Aggregate) group.get(this);
            if (agg == null) {
                agg = getInstance();
            }
            Object obj = agg.getResult();
            if (obj == null) {
                return ValueNull.INSTANCE;
            }
            return DataType.convertToValue(session, obj, dataType);
        } catch (SQLException e) {
            throw DbException.convert(e);
        }
    }

    @Override
    public void updateAggregate(Session session) {
        HashMap<Expression, Object> group = select.getCurrentGroup();
        if (group == null) {
            // this is a different level (the enclosing query)
            return;
        }

        int groupRowId = select.getCurrentGroupRowId();
        if (lastGroupRowId == groupRowId) {
            // already visited
            return;
        }
        lastGroupRowId = groupRowId;

        Aggregate agg = (Aggregate) group.get(this);
        try {
            if (agg == null) {
                agg = getInstance();
                group.put(this, agg);
            }
            Object[] argValues = new Object[args.length];
            Object arg = null;
            for (int i = 0, len = args.length; i < len; i++) {
                Value v = args[i].getValue(session);
                v = v.convertTo(argTypes[i]);
                arg = v.getObject();
                argValues[i] = arg;
            }
            if (args.length == 1) {
                agg.add(arg);
            } else {
                agg.add(argValues);
            }
        } catch (SQLException e) {
            throw DbException.convert(e);
        }
    }

    
    @Override
    public String exportParameters(TableFilter filter, List<Value> container) {
        StatementBuilder buff = new StatementBuilder();
        buff.append(Parser.quoteIdentifier(userAggregate.getName())).append('(');
        for (Expression e : args) {
            buff.appendExceptFirst(", ");
            buff.append(e.exportParameters(filter,container));
        }
        return buff.append(')').toString();
    }

}
