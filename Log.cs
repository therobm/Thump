using System;
using System.Diagnostics;
using System.IO;
using Microsoft.Maui.Storage;

namespace Thump
{
	public static class Log
	{
		private static readonly object s_fileLock = new object();
		private static string s_logFilePath = "";

		public static void Info(string message)
		{
			Write("INFO", message);
		}

		public static void Warn(string message)
		{
			Write("WARN", message);
		}

		public static void Error(string message)
		{
			Write("ERROR", message);
		}

		public static void Exception(Exception ex)
		{
			Write("EXCEPTION", ex.ToString());
		}

		public static string GetLogFilePath()
		{
			lock (s_fileLock)
			{
				return ResolveLogFilePath();
			}
		}

		private static void Write(string level, string message)
		{
			string line = DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss") + " [" + level + "] " + message;
			Debug.WriteLine(line);

			lock (s_fileLock)
			{
				try
				{
					string path = ResolveLogFilePath();
					bool oversized = File.Exists(path) && new FileInfo(path).Length > 2_000_000;
					if (oversized)
					{
						File.WriteAllText(path, "");
					}
					File.AppendAllText(path, line + Environment.NewLine);
				}
				catch (Exception ex)
				{
					Debug.WriteLine("[EXCEPTION] Log file write failed: " + ex);
				}
			}
		}

		private static string ResolveLogFilePath()
		{
			if (string.IsNullOrEmpty(s_logFilePath))
			{
				string directory = FileSystem.AppDataDirectory;
				if (!Directory.Exists(directory))
				{
					Directory.CreateDirectory(directory);
				}
				s_logFilePath = Path.Combine(directory, "thump.log");
			}
			return s_logFilePath;
		}
	}
}
