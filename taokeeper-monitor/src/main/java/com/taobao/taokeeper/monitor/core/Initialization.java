package com.taobao.taokeeper.monitor.core;

import com.taobao.taokeeper.common.GlobalInstance;
import com.taobao.taokeeper.common.SystemInfo;
import com.taobao.taokeeper.common.constant.SystemConstant;
import com.taobao.taokeeper.dao.AlarmSettingsDAO;
import com.taobao.taokeeper.dao.SettingsDAO;
import com.taobao.taokeeper.dao.ZooKeeperClusterDAO;
import com.taobao.taokeeper.model.AlarmSettings;
import com.taobao.taokeeper.model.TaoKeeperSettings;
import com.taobao.taokeeper.model.ZooKeeperCluster;
import com.taobao.taokeeper.model.type.Message;
import com.taobao.taokeeper.monitor.core.task.*;
import com.taobao.taokeeper.monitor.core.task.runable.ClientThroughputStatJob;
import com.taobao.taokeeper.monitor.web.AlarmSettingsController;
import com.taobao.taokeeper.reporter.alarm.GeneralMessageSender;
import common.toolkit.java.constant.BaseConstant;
import common.toolkit.java.exception.DaoException;
import common.toolkit.java.util.ObjectUtil;
import common.toolkit.java.util.StringUtil;
import common.toolkit.java.util.ThreadUtil;
import common.toolkit.java.util.db.DbcpUtil;
import common.toolkit.java.util.number.IntegerUtil;
import common.toolkit.java.util.system.SystemUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.context.ContextLoader;
import org.springframework.web.context.WebApplicationContext;

import javax.servlet.Servlet;
import javax.servlet.http.HttpServlet;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Timer;

/**
 * Description: System Initialization
 * @author yinshi.nc
 * @Date 2011-10-27
 */
public class Initialization extends HttpServlet implements Servlet {

	private static final long serialVersionUID = 1L;
	private static final Logger LOG = LoggerFactory.getLogger( Initialization.class );

	public void init() {

		/** Init threadpool */
		ThreadPoolManager.init();

		initSystem();

		// Start the job of dump db info to memeory
		Thread zooKeeperClusterMapDumpJobThread = new Thread( new ZooKeeperClusterMapDumpJob() );
		zooKeeperClusterMapDumpJobThread.start();
		try {
			// 这里等待一下，因为第一次一定要dump成功，
			// TODO 这个等待逻辑要改。
			Thread.sleep( 5000 );
		} catch ( InterruptedException e ) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		ThreadUtil.startThread( new ClientThroughputStatJob() );

		/** 启动ZooKeeper数据修改通知检测 */
		ThreadUtil.startThread( new ZooKeeperALiveCheckerJob() );

		/** 启动ZooKeeper集群状态收集 */
		ThreadUtil.startThread( new ZooKeeperStatusCollectJob() );

		/** 收集机器CPU LOAD MEMEORY */
		ThreadUtil.startThread( new HostPerformanceCollectTask() );

        /** */
        ThreadUtil.startThread( new HostPerformanceCollectTask() );

        Timer timer = new Timer();
		//开启ZooKeeper Node的Path检查
		timer.schedule( new ZooKeeperNodeChecker(), 5000, //
				           BaseConstant.MILLISECONDS_OF_ONE_HOUR  * 
				           SystemConstant.HOURS_RATE_OF_ZOOKEEPER_NODE_CHECK  );


        //开启ZooKeeper RT monitor
        /*
        timer.schedule( new ZooKeeperRTCollectJob(), 5000, //
                BaseConstant.MILLISECONDS_OF_ONE_MINUTE  *
                        SystemConstant.MINS_RATE_OF_ZOOKEEPER_RT_MONITOR);
          */

		//ThreadUtil.startThread( new CheckerJob( ) );

		
//		ThreadUtil.startThread( new CheckerJob( "/jingwei-v2/tasks/DAILY-TMALL-DPC-META/locks" ) );
		

		LOG.info( "*********************************************************" );
		LOG.info( "****************TaoKeeper Startup Success****************" );
		LOG.info( "*********************************************************" );
		LOG.info( "任何建议与问题，请到 http://jm-blog.aliapp.com/?p=1450 进行反馈。" );
	}

	/**
	 * 从数据库加载并初始化系统配置
	 */
	private void initSystem() {

		LOG.info( "=================================Start to init system===========================" );
		Properties properties = null;
		try {
			properties = SystemUtil.loadProperty();
			if ( ObjectUtil.isBlank( properties ) )
				throw new Exception( "Please defined,such as -DconfigFilePath=\"W:\\TaoKeeper\\taokeeper\\config\\config-test.properties\"" );
		} catch ( Exception e ) {
			LOG.error( e.getMessage() );
			throw new RuntimeException( e.getMessage(), e.getCause() );
		}

		SystemInfo.envName = StringUtil.defaultIfBlank( properties.getProperty( "systemInfo.envName" ), "TaoKeeper-Deploy" );

		DbcpUtil.driverClassName = StringUtil.defaultIfBlank( properties.getProperty( "dbcp.driverClassName" ), "com.mysql.jdbc.Driver" );
		DbcpUtil.dbJDBCUrl = StringUtil.defaultIfBlank( properties.getProperty( "dbcp.dbJDBCUrl" ), "jdbc:mysql://127.0.0.1:3306/taokeeper" );
		DbcpUtil.characterEncoding = StringUtil.defaultIfBlank( properties.getProperty( "dbcp.characterEncoding" ), "UTF-8" );
		DbcpUtil.username = StringUtil.trimToEmpty( properties.getProperty( "dbcp.username" ) );
		DbcpUtil.password = StringUtil.trimToEmpty( properties.getProperty( "dbcp.password" ) );
		DbcpUtil.maxActive = IntegerUtil.defaultIfError( properties.getProperty( "dbcp.maxActive" ), 30 );
		DbcpUtil.maxIdle = IntegerUtil.defaultIfError( properties.getProperty( "dbcp.maxIdle" ), 10 );
		DbcpUtil.maxWait = IntegerUtil.defaultIfError( properties.getProperty( "dbcp.maxWait" ), 10000 );

		SystemConstant.dataStoreBasePath = StringUtil.defaultIfBlank( properties.getProperty( "SystemConstent.dataStoreBasePath" ),
				"/home/yinshi.nc/taokeeper-monitor/" );
		SystemConstant.userNameOfSSH = StringUtil.defaultIfBlank( properties.getProperty( "SystemConstant.userNameOfSSH" ), "admin" );
		SystemConstant.passwordOfSSH = StringUtil.defaultIfBlank( properties.getProperty( "SystemConstant.passwordOfSSH" ), "123456" );
		SystemConstant.portOfSSH = IntegerUtil.defaultIfError( properties.getProperty( "SystemConstant.portOfSSH" ), 22 );

		SystemConstant.IP_OF_MESSAGE_SEND = StringUtil.trimToEmpty( properties.getProperty( "SystemConstant.IP_OF_MESSAGE_SEND" ) );

		
		LOG.info( "=================================Finish init system===========================" );
		WebApplicationContext wac = ContextLoader.getCurrentWebApplicationContext();
		SettingsDAO settingsDAO = ( SettingsDAO ) wac.getBean( "taoKeeperSettingsDAO" );
		AlarmSettingsDAO alarmSettingsDAO = (AlarmSettingsDAO) wac.getBean( "alarmSettingsDAO" );
		AlarmSettings alarmSettings = null;
			// 根据clusterId来获取一个zk集群
		ZooKeeperClusterDAO zooKeeperClusterDAO = ( ZooKeeperClusterDAO ) wac.getBean( "zooKeeperClusterDAO" );
		try {
			List< ZooKeeperCluster > zooKeeperClusterSet = null;
			Map< Integer, ZooKeeperCluster > zooKeeperClusterMap = GlobalInstance.getAllZooKeeperCluster();
			if ( null == zooKeeperClusterMap ) {
				zooKeeperClusterSet = zooKeeperClusterDAO.getAllDetailZooKeeperCluster();
			} else {
				zooKeeperClusterSet = new ArrayList< ZooKeeperCluster >();
				zooKeeperClusterSet.addAll( zooKeeperClusterMap.values() );
			}

			if ( null == zooKeeperClusterSet || zooKeeperClusterSet.isEmpty() ) {
				LOG.warn( "没事配置任何ZooKeeper集群信息，没有必要进行节点的Path检查" );
			} else {
				for ( final ZooKeeperCluster zookeeperCluster : zooKeeperClusterSet ) { // 对每个cluster处理

					alarmSettings = alarmSettingsDAO.getAlarmSettingsByCulsterId( zookeeperCluster.getClusterId() );

				}// for each cluster
			}
		} catch ( DaoException daoException ) {
			LOG.warn( "Error when handle data base" + daoException.getMessage() );
		} catch ( Exception e ) {
			e.printStackTrace();
		}
		//ThreadPoolManager.addJobToMessageSendExecutor( new GeneralMessageSender( alarmSettings, "TaoKeeper启动", "TaoKeeper启动" ) );

			
		TaoKeeperSettings taoKeeperSettings = null;
		try {
			taoKeeperSettings = settingsDAO.getTaoKeeperSettingsBySettingsId( 1 );
		} catch ( DaoException e ) {
			e.printStackTrace();
		}
		if ( null != taoKeeperSettings )
			GlobalInstance.taoKeeperSettings = taoKeeperSettings;

	}

}
