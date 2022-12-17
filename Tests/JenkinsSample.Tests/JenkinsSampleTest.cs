using JenkinsSample;
using Microsoft.VisualStudio.TestTools.UnitTesting;

namespace JenkinsSample.Tests
{
    [TestClass]
    public class JenkinsSampleTest
    {
        [TestMethod]
        public void JenkinsSampleCodeTest()
        {
            Equals(JenkinsSample.JenkinsSampleCode(), "JenkinsSample!");
        }
    }
}