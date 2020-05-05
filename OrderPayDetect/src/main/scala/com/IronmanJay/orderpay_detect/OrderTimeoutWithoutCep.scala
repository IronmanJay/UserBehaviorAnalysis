package com.IronmanJay.orderpay_detect

import org.apache.flink.api.common.state.{ValueState, ValueStateDescriptor}
import org.apache.flink.streaming.api.TimeCharacteristic
import org.apache.flink.streaming.api.functions.KeyedProcessFunction
import org.apache.flink.streaming.api.scala._
import org.apache.flink.util.Collector

object OrderTimeoutWithoutCep {

  val orderTimeoutOutputTag = new OutputTag[OrderResult]("orderTimeout")

  def main(args: Array[String]): Unit = {
    val env = StreamExecutionEnvironment.getExecutionEnvironment
    env.setStreamTimeCharacteristic(TimeCharacteristic.EventTime)
    env.setParallelism(1)
    // 读取订单数据
    val orderEventStream = env.readTextFile("D:\\Software\\UserBehaviorAnalysis\\OrderPayDetect\\src\\main\\resources\\OrderLog.csv")
      .map(data => {
        val dataArray = data.split(",")
        OrderEvent(dataArray(0).trim.toLong, dataArray(1).trim, dataArray(2).trim, dataArray(3).trim.toLong)
      })
      .assignAscendingTimestamps(_.eventTime * 1000L)
      .keyBy(_.orderId)
    // 定义process function进行超时检测
    //    val timeoutWarningStream = orderEventStream.process(new OrderTimeoutWarning())
    val orderResultStream = orderEventStream.process(new OrderPayMatch())
    orderResultStream.print("payed")
    orderResultStream.getSideOutput(orderTimeoutOutputTag).print("timeout")
    env.execute("order timeout without cep job")
  }

  class OrderPayMatch() extends KeyedProcessFunction[Long, OrderEvent, OrderResult] {

    lazy val isPayedState: ValueState[Boolean] = getRuntimeContext.getState(new ValueStateDescriptor[Boolean]("ispayed-state", classOf[Boolean]))

    // 保存定时器的时间戳为状态
    lazy val timerState: ValueState[Long] = getRuntimeContext.getState(new ValueStateDescriptor[Long]("timer-state", classOf[Long]))

    override def processElement(value: OrderEvent, ctx: KeyedProcessFunction[Long, OrderEvent, OrderResult]#Context, out: Collector[OrderResult]): Unit = {
      // 先读取状态
      val isPayed = isPayedState.value()
      val timerTs = timerState.value()
      // 根据事件的类型进行分类判断，做不同的处理逻辑
      if (value.eventType == "create") {
        // 1、如果是create事件，接下来判断pay是否来过
        if (isPayed) {
          // 1.1如果已经pay过，匹配成功，输出主流数据。清空状态
          out.collect(OrderResult(value.orderId, "payed successfully"))
          ctx.timerService().deleteEventTimeTimer(timerTs)
          isPayedState.clear()
          timerState.clear()
        } else {
          // 1.2如果没有pay过，注册定时器等待pay的到来
          val ts = value.eventTime * 1000L + 15 * 60 * 1000L
          ctx.timerService().registerEventTimeTimer(ts)
          timerState.update(ts)
        }
      } else if (value.eventType == "pay") {
        // 2、如果是pay事件，那么判断是否create过，用timer表示
        if (timerTs > 0) {
          // 2.1 如果有定时器，说明已经有create来过
          // 继续判断，是否超过了timeout时间
          if (timerTs > value.eventTime * 1000L) {
            // 2.1.1 如果定时器时间还没到，那么输出成功匹配
            out.collect(OrderResult(value.orderId, "payed successfully"))
          } else {
            // 2.1.2 如果当前pay的时间已经超时，那么输出到测输出流
            ctx.output(orderTimeoutOutputTag, OrderResult(value.orderId, "payed but already timeout"))
          }
          // 输出结束，清空状态
          ctx.timerService().deleteEventTimeTimer(timerTs)
          isPayedState.clear()
          timerState.clear()
        } else {
          // 2.2 pay先到了，更新状态，注册定时器等待create
          isPayedState.update(true)
          ctx.timerService().registerEventTimeTimer(value.eventTime * 1000L)
          timerState.update(value.eventTime * 1000L)
        }
      }
    }

    override def onTimer(timestamp: Long, ctx: KeyedProcessFunction[Long, OrderEvent, OrderResult]#OnTimerContext, out: Collector[OrderResult]): Unit = {
      // 根据状态的值，判断哪个数据没来
      if (isPayedState.value()) {
        // 如果为true，表示pay先到了，没等到create
        ctx.output(orderTimeoutOutputTag, OrderResult(ctx.getCurrentKey, "already payed but not found create log"))
      } else {
        // 表示create到了，没等到pay
        ctx.output(orderTimeoutOutputTag, OrderResult(ctx.getCurrentKey, "order timeout"))
      }
      isPayedState.clear()
      timerState.clear()
    }

  }

}

// 实现自定义的处理函数
class OrderTimeoutWarning() extends KeyedProcessFunction[Long, OrderEvent, OrderResult] {

  // 保存pay是否来过的状态
  lazy val isPayedState: ValueState[Boolean] = getRuntimeContext.getState(new ValueStateDescriptor[Boolean]("ispayed-state", classOf[Boolean]))

  override def processElement(value: OrderEvent, ctx: KeyedProcessFunction[Long, OrderEvent, OrderResult]#Context, out: Collector[OrderResult]): Unit = {
    // 先取出状态标识位
    val isPayed = isPayedState.value()

    if (value.eventType == "create" && !isPayed) {
      // 如果遇到了create事件，并且pay没有来过，注册定时器开始等待
      ctx.timerService().registerEventTimeTimer(value.eventTime * 1000L + 15 * 60 * 1000L)
    } else if (value.eventType == "pay") {
      // 如果是pay事件，直接把状态改为true
      isPayedState.update(true)
    }
  }

  override def onTimer(timestamp: Long, ctx: KeyedProcessFunction[Long, OrderEvent, OrderResult]#OnTimerContext, out: Collector[OrderResult]): Unit = {
    // 判断isPayed是否为true
    val isPayed = isPayedState.value()
    if (isPayed) {
      out.collect(OrderResult(ctx.getCurrentKey, "order payed successfully"))
    } else {
      out.collect(OrderResult(ctx.getCurrentKey, "order timeout"))
    }
    // 清空状态
    isPayedState.clear()
  }

}
