package com.atguigu.gmall0401.order.service.impl;

import com.alibaba.dubbo.config.annotation.Service;
import com.alibaba.fastjson.JSON;
import com.atguigu.gmall0401.bean.OrderDetail;
import com.atguigu.gmall0401.bean.OrderInfo;
import com.atguigu.gmall0401.enums.OrderStatus;
import com.atguigu.gmall0401.enums.ProcessStatus;
import com.atguigu.gmall0401.order.mapper.OrderDetailMapper;
import com.atguigu.gmall0401.order.mapper.OrderInfoMapper;
import com.atguigu.gmall0401.service.OrderService;
import com.atguigu.gmall0401.util.RedisUtil;
import org.apache.commons.beanutils.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.transaction.annotation.Transactional;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.Transaction;
import sun.misc.UUDecoder;
import tk.mybatis.mapper.entity.Example;

import java.lang.reflect.InvocationTargetException;
import java.util.*;

@Service
public class OrderServiceImpl implements OrderService {

    @Autowired
    RedisUtil redisUtil;

    @Autowired
    OrderDetailMapper orderDetailMapper;

    @Autowired
    OrderInfoMapper orderInfoMapper;

    @Override
    @Transactional
    public String saveOrder(OrderInfo orderInfo) {

        orderInfoMapper.insertSelective(orderInfo);

        List<OrderDetail> orderDetailList = orderInfo.getOrderDetailList();
        for (OrderDetail orderDetail : orderDetailList) {
            orderDetail.setOrderId(orderInfo.getId());
            orderDetailMapper.insertSelective(orderDetail);
        }

        return orderInfo.getId();

    }

    @Override
    public OrderInfo getOrderInfo(String orderId) {
        OrderInfo orderInfo = orderInfoMapper.selectByPrimaryKey(orderId);
        OrderDetail orderDetail = new OrderDetail();
        orderDetail.setOrderId(orderId);
        List<OrderDetail> orderDetailList = orderDetailMapper.select(orderDetail);
        orderInfo.setOrderDetailList(orderDetailList);
        return orderInfo;
    }

    @Override
    public String genToken(String userId) {
        //token  type   String  key   user:10201:trade_code  value token
        String token = UUID.randomUUID().toString();
        String tokenKey="user:"+userId+":trade_code";
        Jedis jedis = redisUtil.getJedis();
        jedis.setex(tokenKey,10*60,token);
        jedis.close();

        return token;
    }

        @Override
        public boolean verifyToken(String userId, String token) {
            String tokenKey="user:"+userId+":trade_code";
            Jedis jedis = redisUtil.getJedis();
            String tokenExists = jedis.get(tokenKey);
            jedis.watch(tokenKey);
            Transaction transaction = jedis.multi();
            if(tokenExists!=null&&tokenExists.equals(token)){
                transaction.del(tokenKey);
            }
            List<Object> list = transaction.exec();
            if(list!=null&&list.size()>0&&(Long)list.get(0)==1L){
                return true;
            }else{
                return false;
            }

        }



    @Override
    public void updateStatus(String orderId, ProcessStatus processStatus, OrderInfo... orderInfos) {
        OrderInfo orderInfo = new OrderInfo();
        if(orderInfos!=null&& orderInfos.length>0 ){ //???????????????????????????????????????????????? ????????????????????????????????????
            orderInfo=orderInfos[0];
        }
        orderInfo.setProcessStatus(processStatus);
        orderInfo.setOrderStatus(processStatus.getOrderStatus());
        orderInfo.setId(orderId);
        orderInfoMapper.updateByPrimaryKeySelective(orderInfo);

    }

    public List<Integer> checkExpiredCoupon(){
        return Arrays.asList(1,2,3,4,5,6,7);
    }


    @Override
    public List<OrderInfo> getOrderListByUser(String userId) {
        // ??????????????????
        //??????????????? ?????????

        Example example=new Example(OrderInfo.class);
        example.setOrderByClause("id desc");
        example.createCriteria().andEqualTo("userId",userId);

        List<OrderInfo> orderInfoList = orderInfoMapper.selectByExample(example);
        for (Iterator<OrderInfo> iterator = orderInfoList.iterator(); iterator.hasNext(); ) {
            OrderInfo orderInfo = iterator.next();
            OrderDetail orderDetailQuery=new OrderDetail();
            orderDetailQuery.setOrderId(orderInfo.getId());
            List<OrderDetail> orderDetailList = orderDetailMapper.select(orderDetailQuery);
            orderInfo.setOrderDetailList(orderDetailList);

            if(orderInfo.getOrderStatus()== OrderStatus.SPLIT){  //???????????????????????? ?????????????????????
                List<OrderInfo> orderSubList = new ArrayList<>();
                for (OrderInfo subOrderInfo : orderInfoList) {
                    if(orderInfo.getId().equals(subOrderInfo.getParentOrderId())){
                        orderSubList.add(subOrderInfo);

                    }
                }
                orderInfo.setOrderSubList(orderSubList);
            }

        }

        return orderInfoList;
    }

    @Override
    public List<Map> orderSplit(String orderId, String wareSkuMapJson) {
        // 1  ??????orderId ????????? ????????????
        OrderInfo orderInfoParent = getOrderInfo(orderId);

        // 2  wareSkuMap => list   ????????????list
        List<Map> mapList = JSON.parseArray(wareSkuMapJson, Map.class);

        List<Map> wareParamMapList=new ArrayList<>();
        // ???????????? ???  ??????????????????    ????????????2?????????   ????????? orderInfo  ???????????? orderDetail
        for (Map wareSkuMap : mapList) {
            //  3 ????????? ???????????? orderInfo   ???????????????????????????    ????????? ??????  id parent_order_id
            OrderInfo orderInfoSub = new OrderInfo();
            try {
                BeanUtils.copyProperties(orderInfoSub,orderInfoParent);
            } catch (IllegalAccessException e) {
                e.printStackTrace();
            } catch (InvocationTargetException e) {
                e.printStackTrace();
            }


            //  4   ?????????  ????????????  orderDetail
            List<String > skuIdList = (List<String >)wareSkuMap.get("skuIds");  // ????????????????????????
            List<OrderDetail> orderDetailList = orderInfoParent.getOrderDetailList(); //???????????????????????????????????????
            ArrayList<OrderDetail> orderDetailSubList = new ArrayList<>();  // ?????????????????????????????????
            for (String skuId : skuIdList) {
                for (OrderDetail orderDetail : orderDetailList) {
                    if(skuId.equals(orderDetail.getSkuId())){
                        OrderDetail orderDetailSub = new OrderDetail();
                        try {
                            BeanUtils.copyProperties(orderDetailSub,orderDetail);
                        } catch (IllegalAccessException e) {
                            e.printStackTrace();
                        } catch (InvocationTargetException e) {
                            e.printStackTrace();
                        }
                        orderDetailSub.setId(null);
                        orderDetailSub.setOrderId(null);
                        orderDetailSubList.add(orderDetailSub);
                    }
                }
            }

            //  5 ???????????????????????????    ?????? ??????  id ?????? parent_order_id   ?????? ?????????
            orderInfoSub.setOrderDetailList(orderDetailSubList);
            orderInfoSub.setId(null);
            orderInfoSub.sumTotalAmount();
            orderInfoSub.setParentOrderId(orderInfoParent.getId());

            saveOrder(orderInfoSub);


            //  6 ???????????? ???????????? ???????????? ???????????????  map

            Map wareParamMap = initWareParamJsonFormOrderInfo(orderInfoSub);
            wareParamMap.put("wareId",wareSkuMap.get("wareId"));

            wareParamMapList.add(wareParamMap);

            // 7 ????????????  ????????????????????????

            updateStatus(orderId,ProcessStatus.SPLIT);
        }

         //  ????????????List<Map> ??????


        return wareParamMapList;
    }

    @Async
    public void handleExpiredCoupon(Integer id){
        try {
            System.out.println("????????????"+ id +"????????????");
            Thread.sleep(1000);

            System.out.println("????????????"+ id +"??????");
            Thread.sleep(1000);

        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }



    /**
     * ????????? ?????????????????????????????????
     * @param orderId
     * @return
     */
    public   Map initWareParamJson(String orderId){
        OrderInfo orderInfo = getOrderInfo(orderId);

        Map map = initWareParamJsonFormOrderInfo(orderInfo);
        return  map;

    }

    private  Map initWareParamJsonFormOrderInfo(OrderInfo orderInfo) {
        Map  paramMap=new HashMap();

        paramMap.put("orderId",orderInfo.getId());
        paramMap.put("consignee",orderInfo.getConsignee());
        paramMap.put("consigneeTel",orderInfo.getConsigneeTel());
        paramMap.put("orderComment",orderInfo.getOrderComment());
        paramMap.put("orderBody",orderInfo.genSubject());
        paramMap.put("deliveryAddress",orderInfo.getDeliveryAddress());
        paramMap.put("paymentWay","2");
        List<Map> details=new ArrayList();
        for (OrderDetail orderDetail : orderInfo.getOrderDetailList() ){
            HashMap<String, String> orderDetailMap = new HashMap<>();
            orderDetailMap.put("skuId",orderDetail.getSkuId());
            orderDetailMap.put("skuNum",orderDetail.getSkuNum().toString());
            orderDetailMap.put("skuName",orderDetail.getSkuName());
            details.add(orderDetailMap);
        }
        paramMap.put("details",details );
        return paramMap;
    }

}
