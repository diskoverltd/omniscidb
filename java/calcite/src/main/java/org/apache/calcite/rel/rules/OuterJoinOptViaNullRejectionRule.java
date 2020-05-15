/*
 * Copyright 2020 OmniSci, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.calcite.rel.rules;

import org.apache.calcite.plan.RelOptRuleCall;
import org.apache.calcite.plan.hep.HepRelVertex;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Join;
import org.apache.calcite.rel.core.JoinRelType;
import org.apache.calcite.rel.logical.LogicalFilter;
import org.apache.calcite.rel.logical.LogicalJoin;
import org.apache.calcite.rex.RexCall;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.tools.RelBuilder;
import org.apache.calcite.tools.RelBuilderFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class OuterJoinOptViaNullRejectionRule extends QueryOptimizationRules {
  // goal: relax full outer join to either left or inner joins
  // consider two tables 'foo(a int, b int)' and 'bar(c int, d int)'
  // foo = {(1,3), (2,4), (NULL, 5)} // bar = {(1,2), (4, 3), (NULL, 5)}

  // 1. full outer join -> left
  //      : select * from foo full outer join bar on a = c where a is not null;
  //      = select * from foo left outer join bar on a = c where a is not null;

  // 2. full outer join -> inner
  //      : select * from foo full outer join bar on a = c where a is not null and c is
  //      not null; = select * from foo join bar on a = c; (or select * from foo, bar
  //      where a = c;)

  // 3. left outer join --> inner
  //      : select * from foo left outer join bar on a = c where c is not null;
  //      = select * from foo join bar on a = c; (or select * from foo, bar where a = c;)

  // null rejection: "col IS NOT NULL" or "col > NULL_INDICATOR" in WHERE clause
  // i.e., col > 1 must reject any tuples having null value in a col column

  // todo(yoonmin): runtime query optimization via statistic
  //  in fact, we can optimize more broad range of the query having outer joins
  //  by using filter predicates on join tables (but not on join cols)
  //  because such filter conditions could affect join tables and
  //  they can make join cols to be null rejected

  public static Set<String> visitedJoinMemo = new HashSet<>();

  public OuterJoinOptViaNullRejectionRule(RelBuilderFactory relBuilderFactory) {
    super(operand(RelNode.class, operand(Join.class, null, any())),
            relBuilderFactory,
            "OuterJoinOptViaNullRejectionRule");
    clearMemo();
  }

  void clearMemo() {
    visitedJoinMemo.clear();
  }

  @Override
  public void onMatch(RelOptRuleCall call) {
    RelNode parentNode = call.rel(0);
    LogicalJoin join = (LogicalJoin) call.rel(1);
    String condString = join.getCondition().toString();
    if (visitedJoinMemo.contains(condString)) {
      return;
    } else {
      visitedJoinMemo.add(condString);
    }
    if (!(join.getCondition() instanceof RexCall)) {
      return; // an inner join
    }
    // an outer join contains its join cond in itself,
    // not in a filter as typical inner join op. does
    RexCall joinCond = (RexCall) join.getCondition();
    Set<RexInputRef> leftJoinCols = new HashSet<>();
    Set<RexInputRef> rightJoinCols = new HashSet<>();

    if (joinCond.getKind() == SqlKind.EQUALS) {
      addJoinCols(joinCond, leftJoinCols, rightJoinCols);
    }

    if (joinCond.getKind() == SqlKind.AND || joinCond.getKind() == SqlKind.OR) {
      for (RexNode n : joinCond.getOperands()) {
        if (n instanceof RexCall) {
          RexCall op = (RexCall) n;
          addJoinCols(op, leftJoinCols, rightJoinCols);
        }
      }
    }

    if (leftJoinCols.isEmpty() || rightJoinCols.isEmpty()) {
      return;
    }

    // find filter node(s)
    RelNode root = call.getPlanner().getRoot();
    List<LogicalFilter> collectedFilterNodes = new ArrayList<>();
    RelNode curNode = root;
    final RelBuilder relBuilder = call.builder();
    // collect filter nodes
    collectFilterCondition(curNode, collectedFilterNodes);
    if (collectedFilterNodes.isEmpty()) {
      return;
    }

    // check whether join column has filter predicate(s)
    // and collect join column info used in target join nodes to be translated
    Set<RexInputRef> nullRejectedLeftJoinCols = new HashSet<>();
    Set<RexInputRef> nullRejectedRightJoinCols = new HashSet<>();
    for (LogicalFilter filter : collectedFilterNodes) {
      List<RexNode> filterExprs = filter.getChildExps();
      for (RexNode node : filterExprs) {
        if (node instanceof RexCall) {
          RexCall curExpr = (RexCall) node;
          if (curExpr.getKind() == SqlKind.AND || curExpr.getKind() == SqlKind.OR) {
            for (RexNode n : curExpr.getOperands()) {
              if (n instanceof RexCall) {
                RexCall c = (RexCall) n;
                addNullRejectedJoinCols(c,
                        leftJoinCols,
                        rightJoinCols,
                        nullRejectedLeftJoinCols,
                        nullRejectedRightJoinCols);
              }
            }
          } else {
            if (curExpr instanceof RexCall) {
              RexCall c = (RexCall) curExpr;
              addNullRejectedJoinCols(c,
                      leftJoinCols,
                      rightJoinCols,
                      nullRejectedLeftJoinCols,
                      nullRejectedRightJoinCols);
            }
          }
        }
      }
    }
    Boolean leftNullRejected = false;
    Boolean rightNullRejected = false;
    if (!nullRejectedLeftJoinCols.isEmpty()
            && leftJoinCols.containsAll(nullRejectedLeftJoinCols)) {
      leftNullRejected = true;
    }
    if (!nullRejectedRightJoinCols.isEmpty()
            && rightJoinCols.containsAll(nullRejectedRightJoinCols)) {
      rightNullRejected = true;
    }
    if (!leftNullRejected && !rightNullRejected) {
      return;
    }

    // relax outer join condition depending on null rejected cols
    RelNode newJoinNode = null;
    Boolean needTransform = false;
    if (join.getJoinType() == JoinRelType.FULL) {
      // 1) full -> left
      if (leftNullRejected && !rightNullRejected) {
        newJoinNode = join.copy(join.getTraitSet(),
                join.getCondition(),
                join.getLeft(),
                join.getRight(),
                JoinRelType.LEFT,
                join.isSemiJoinDone());
        needTransform = true;
      }

      // 2) full -> inner
      if (leftNullRejected && rightNullRejected) {
        newJoinNode = join.copy(join.getTraitSet(),
                join.getCondition(),
                join.getLeft(),
                join.getRight(),
                JoinRelType.INNER,
                join.isSemiJoinDone());
        needTransform = true;
      }
    } else if (join.getJoinType() == JoinRelType.LEFT) {
      // 3) left -> inner
      if (rightNullRejected) {
        newJoinNode = join.copy(join.getTraitSet(),
                join.getCondition(),
                join.getLeft(),
                join.getRight(),
                JoinRelType.INNER,
                join.isSemiJoinDone());
        needTransform = true;
      }
    }
    if (needTransform) {
      relBuilder.push(newJoinNode);
      parentNode.replaceInput(0, newJoinNode);
      call.transformTo(parentNode);
    }
    return;
  }

  void addJoinCols(RexCall joinCond,
          Set<RexInputRef> leftJoinCols,
          Set<RexInputRef> rightJoinCols) {
    if (joinCond.getOperands().size() != 2
            || !(joinCond.getOperands().get(0) instanceof RexInputRef)
            || !(joinCond.getOperands().get(1) instanceof RexInputRef)) {
      return;
    }
    RexInputRef leftJoinCol = (RexInputRef) joinCond.getOperands().get(0);
    RexInputRef rightJoinCol = (RexInputRef) joinCond.getOperands().get(1);
    leftJoinCols.add(leftJoinCol);
    rightJoinCols.add(rightJoinCol);
    return;
  }

  void addNullRejectedJoinCols(RexCall joinCol,
          Set<RexInputRef> leftJoinCols,
          Set<RexInputRef> rightJoinCols,
          Set<RexInputRef> nullRejectedLeftJoinCols,
          Set<RexInputRef> nullRejectedRightJoinCols) {
    if (joinCol.getKind() != SqlKind.IS_NULL
            && joinCol.getOperands().get(0) instanceof RexInputRef) {
      RexInputRef col = (RexInputRef) joinCol.getOperands().get(0);
      Boolean l = leftJoinCols.contains(col);
      Boolean r = rightJoinCols.contains(col);
      if (l && !r) {
        nullRejectedLeftJoinCols.add(col);
        return;
      }
      if (r && !l) {
        nullRejectedRightJoinCols.add(col);
        return;
      }
    }
  }

  void collectFilterCondition(RelNode curNode, List<LogicalFilter> collectedFilterNodes) {
    if (curNode instanceof HepRelVertex) {
      curNode = ((HepRelVertex) curNode).getCurrentRel();
    }
    if (curNode instanceof LogicalFilter) {
      collectedFilterNodes.add((LogicalFilter) curNode);
    }
    if (curNode.getInputs().size() == 0) {
      // end of the query plan, move out
      return;
    }
    for (int i = 0; i < curNode.getInputs().size(); i++) {
      collectFilterCondition(curNode.getInput(i), collectedFilterNodes);
    }
  }
}