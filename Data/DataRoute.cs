
using Microsoft.Maui.ApplicationModel;
using System;

namespace Thump.Data
{
	public enum eRouteCachingMethod
	{
		NetworkAuthorative,
		LocalFirst,
	}

	public class DataRoute
	{
		public static bool s_bCacheEnabled = true;

		public eRouteCachingMethod m_cacheType = eRouteCachingMethod.NetworkAuthorative;
		protected ThumpData m_dataProvider;
		public DataRoute(ThumpData dataProvider, eRouteCachingMethod cacheType)
		{
			m_dataProvider = dataProvider;
			m_cacheType = cacheType;
		}

		protected void QueryDB(Action dataFunction)
		{
			m_dataProvider.Cache.ExecuteSync(dataFunction);
		}
		protected bool IsOnline()
		{
			return m_dataProvider.Pulse.IsOnline();
		}
	}

	public class DataRoute<T> : DataRoute where T : class
	{
		public Action<Action<T>> m_queryNetwork;
		public Func<T> m_queryDatabase;
		public Action<T> m_storeDatabase;
		public Predicate<T> m_dataValidator;

		public DataRoute(ThumpData dataProvider, eRouteCachingMethod cacheType, Action<Action<T>> queryNetwork, Func<T> queryDatabase, Action<T> storeDatabase, Predicate<T> dataValidator) : base(dataProvider, cacheType)
		{
			m_queryNetwork = queryNetwork;
			m_queryDatabase = queryDatabase;
			m_storeDatabase = storeDatabase;
			m_dataValidator = dataValidator;
		}

		public void GetData(Action<T> callback)
		{
			if (!IsOnline())
			{
                QueryDB(() =>
				{
					T cached = null;
					if (s_bCacheEnabled)
						cached = m_queryDatabase();
					MainThread.BeginInvokeOnMainThread(() => { callback(cached); });
				});
				return;
			}

			if (m_cacheType == eRouteCachingMethod.NetworkAuthorative)
			{
				m_queryNetwork((netData) =>
				{
					T retVal = netData;

                    if (m_dataValidator(retVal))
                    {
                        if (s_bCacheEnabled)
						{
							QueryDB(() => { m_storeDatabase(retVal); });
                        }
                        MainThread.BeginInvokeOnMainThread(() =>
                        {
                            callback(retVal);
                        });
                    }
					else
					{
						if (s_bCacheEnabled)
						{	
							QueryDB(()=>
							{
								retVal = m_queryDatabase();
                                MainThread.BeginInvokeOnMainThread(() => { callback(retVal); });
                            });
                        }
                        else
                        {
                            MainThread.BeginInvokeOnMainThread(() => { callback(retVal); });
                        }
                    }

				});
				return;
			}
			else
			{
				T cacheData = null;
				if (s_bCacheEnabled)
				{ 
				    QueryDB(() => 
					{ 
						cacheData = m_queryDatabase();
                        if (m_dataValidator(cacheData))
                        {
                            MainThread.BeginInvokeOnMainThread(() => { callback(cacheData); });
                        }
                        else
                        {
                            m_queryNetwork((netData) =>
                            {
                                if (m_dataValidator(netData))
                                {
                                    QueryDB(() => { m_storeDatabase(netData); });
                                }
                                MainThread.BeginInvokeOnMainThread(() => { callback(netData); });
                            });
                        }
                    }); 
					return;
				}
				else
				{
					m_queryNetwork((netData) =>
					{
						if (m_dataValidator(netData))
						{   
							QueryDB(() => { m_storeDatabase(netData); });
						}
                        MainThread.BeginInvokeOnMainThread(() => { callback(netData); });
					});
				}
			}
		}
	}

	public class DataRouteID<T> : DataRoute where T : class
	{
		public Action<string, Action<T>> m_queryNetwork;
		public Func<string, T> m_queryDatabase;
		public Action<string, T> m_storeDatabase;
		public Predicate<T> m_dataValidator;

		public DataRouteID(ThumpData dataProvider, eRouteCachingMethod cacheType, Action<string, Action<T>> queryNetwork, Func<string, T> queryDatabase, Action<string, T> storeDatabase, Predicate<T> dataValidator) : base(dataProvider, cacheType)
		{
			m_queryNetwork = queryNetwork;
			m_queryDatabase = queryDatabase;
			m_storeDatabase = storeDatabase;
			m_dataValidator = dataValidator;
		}

		public void GetData(string id, Action<T> callback)
		{
			if (!m_dataProvider.Pulse.IsOnline())
			{
				m_dataProvider.Cache.ExecuteSync(() =>
				{
					T cached = null;
					if (s_bCacheEnabled)
					{	
						cached = m_queryDatabase(id);
					}
					MainThread.BeginInvokeOnMainThread(() => { callback(cached); });
				});
				return;
			}

			if (m_cacheType == eRouteCachingMethod.NetworkAuthorative)
			{
                m_queryNetwork(id, (netData) =>
				{
					T retVal = netData;
                  

                    if (m_dataValidator(retVal))
                    {
                        if (s_bCacheEnabled)
						{ 
							QueryDB(() => { m_storeDatabase(id, retVal); });
						}
                        MainThread.BeginInvokeOnMainThread(() => 
						{
                            callback(retVal); 
						});
                    }
					else
					{
						if (s_bCacheEnabled)
						{
                            QueryDB(() =>
							{ 
								retVal = m_queryDatabase(id);
                                MainThread.BeginInvokeOnMainThread(() => { callback(retVal); });
                            });
						}
						else
                        {
                            MainThread.BeginInvokeOnMainThread(() => { callback(retVal); });
                        }
					}

				});
				return;
			}
            else
            {
                if (s_bCacheEnabled)
                {
                    QueryDB(() =>
                    {
                        T cacheData = m_queryDatabase(id);
                        if (m_dataValidator(cacheData))
                        {
                            MainThread.BeginInvokeOnMainThread(() => { callback(cacheData); });
                        }
                        else
                        {
                            m_queryNetwork(id, (netData) =>
                            {
                                if (m_dataValidator(netData))
                                {
                                    QueryDB(() => { m_storeDatabase(id, netData); });
                                }
                                MainThread.BeginInvokeOnMainThread(() => { callback(netData); });
                            });
                        }
                    });
                    return;
                }
                else
                {
                    m_queryNetwork(id, (netData) =>
                    {
                        if (m_dataValidator(netData))
                        {
                            QueryDB(() => { m_storeDatabase(id, netData); });
                        }
                        MainThread.BeginInvokeOnMainThread(() => { callback(netData); });
                    });
                }
            }
        }
	}
}