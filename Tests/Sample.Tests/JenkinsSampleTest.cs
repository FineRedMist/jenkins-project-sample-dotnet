using CodeLibrarySample;
using Microsoft.VisualStudio.TestTools.UnitTesting;

namespace Sample.Tests
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