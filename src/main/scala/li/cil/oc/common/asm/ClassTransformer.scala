package li.cil.oc.common.asm

import cpw.mods.fml.common.asm.transformers.deobf.FMLDeobfuscatingRemapper
import li.cil.oc.common.asm.template.SimpleComponentImpl
import li.cil.oc.integration.Mods
import net.minecraft.launchwrapper.IClassTransformer
import net.minecraft.launchwrapper.LaunchClassLoader
import org.apache.logging.log4j.LogManager
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree._

import scala.collection.convert.WrapAsJava._
import scala.collection.convert.WrapAsScala._

class ClassTransformer extends IClassTransformer {
  private val loader = classOf[ClassTransformer].getClassLoader.asInstanceOf[LaunchClassLoader]

  private val log = LogManager.getLogger("OpenComputers")

  private lazy val powerTypes = Map[Mods.ModBase, Array[String]](
    Mods.AppliedEnergistics2 -> Array("appeng/api/networking/IGridHost"),
    Mods.Factorization -> Array("factorization/api/IChargeConductor"),
    Mods.Galacticraft -> Array("micdoodle8/mods/galacticraft/api/power/IEnergyHandlerGC"),
    Mods.IndustrialCraft2API -> Array("ic2/api/energy/tile/IEnergySink"),
    Mods.IndustrialCraft2Classic -> Array("ic2classic/api/energy/tile/IEnergySink"),
    Mods.Mekanism -> Array("mekanism/api/energy/IStrictEnergyAcceptor"),
    Mods.CoFHEnergy -> Array("cofh/api/energy/IEnergyHandler"),
    Mods.UniversalElectricity -> Array("universalelectricity/api/core/grid/INodeProvider")
  )

  override def transform(name: String, transformedName: String, basicClass: Array[Byte]): Array[Byte] = {
    var transformedClass = basicClass
    try {
      if (name == "li.cil.oc.common.tileentity.traits.Computer" || name == "li.cil.oc.common.tileentity.Rack") {
        transformedClass = ensureStargateTechCompatibility(transformedClass)
      }
      if (transformedClass != null
        && !name.startsWith("net.minecraft.")
        && !name.startsWith("net.minecraftforge.")
        && !name.startsWith("li.cil.oc.common.asm.")
        && !name.startsWith("li.cil.oc.integration.")) {
        if (name.startsWith("li.cil.oc.")) {
          // Strip foreign interfaces from scala generated classes. This is
          // primarily intended to clean up mix-ins / synthetic classes
          // generated by Scala.
          val classNode = newClassNode(transformedClass)
          val missingInterfaces = classNode.interfaces.filter(!classExists(_))
          for (interfaceName <- missingInterfaces) {
            log.trace(s"Stripping interface $interfaceName from class $name because it is missing.")
          }
          classNode.interfaces.removeAll(missingInterfaces)

          val missingClasses = classNode.innerClasses.filter(clazz => clazz.outerName != null && !classExists(clazz.outerName))
          for (innerClass <- missingClasses) {
            log.trace(s"Stripping inner class ${innerClass.name} from class $name because its type ${innerClass.outerName} is missing.")
          }
          classNode.innerClasses.removeAll(missingClasses)

          val incompleteMethods = classNode.methods.filter(method => missingFromSignature(method.desc).nonEmpty)
          for (method <- incompleteMethods) {
            val missing = missingFromSignature(method.desc).mkString(", ")
            log.trace(s"Stripping method ${method.name} from class $name because the following types in its signature are missing: $missing")
          }
          classNode.methods.removeAll(incompleteMethods)

          // Inject available power interfaces into power acceptors.
          if (classNode.interfaces.contains("li/cil/oc/common/tileentity/traits/PowerAcceptor")) {
            def missingImplementations(interfaceName: String) = {
              val node = classNodeFor(interfaceName)
              if (node == null) Seq(s"Interface ${interfaceName.replaceAll("/", ".")} not found.")
              else node.methods.filterNot(im => classNode.methods.exists(cm => im.name == cm.name && im.desc == cm.desc)).map(method => s"Missing implementation of ${method.name + method.desc}")
            }
            for ((mod, interfaces) <- powerTypes if mod.isAvailable) {
              val missing = interfaces.flatMap(missingImplementations)
              if (missing.isEmpty) {
                interfaces.foreach(classNode.interfaces.add)
              }
              else {
                mod.disablePower()
                log.warn(s"Skipping power support for mod ${mod.id}.")
                missing.foreach(log.warn)
              }
            }
          }

          transformedClass = writeClass(classNode)
        }
        {
          val classNode = newClassNode(transformedClass)
          if (classNode.interfaces.contains("li/cil/oc/api/network/SimpleComponent")) {
            try {
              transformedClass = injectEnvironmentImplementation(classNode)
              log.info(s"Successfully injected component logic into class $name.")
            }
            catch {
              case e: Throwable =>
                log.warn(s"Failed injecting component logic into class $name.", e)
            }
          }
        }
      }
      transformedClass
    }
    catch {
      case t: Throwable =>
        log.warn("Something went wrong!", t)
        basicClass
    }
  }

  private def classExists(name: String) = {
    loader.getClassBytes(name) != null ||
      loader.getClassBytes(FMLDeobfuscatingRemapper.INSTANCE.unmap(name)) != null ||
      (try loader.findClass(name.replace('/', '.')) != null catch {
        case _: ClassNotFoundException => false
      })
  }

  private def missingFromSignature(desc: String) = {
    """L([^;]+);""".r.findAllMatchIn(desc).map(_.group(1)).filter(!classExists(_))
  }

  def ensureStargateTechCompatibility(basicClass: Array[Byte]): Array[Byte] = {
    if (!Mods.StargateTech2.isAvailable) {
      // No SGT2 or version is too old, abstract bus API doesn't exist.
      val classNode = newClassNode(basicClass)
      classNode.interfaces.remove("stargatetech2/api/bus/IBusDevice")
      writeClass(classNode)
    }
    else basicClass
  }

  def injectEnvironmentImplementation(classNode: ClassNode): Array[Byte] = {
    log.trace(s"Injecting methods from Environment interface into ${classNode.name}.")
    if (!isTileEntity(classNode)) {
      throw new InjectionFailedException("Found SimpleComponent on something that isn't a tile entity, ignoring.")
    }

    val template = classNodeFor("li/cil/oc/common/asm/template/SimpleEnvironment")
    if (template == null) {
      throw new InjectionFailedException("Could not find SimpleComponent template!")
    }

    def inject(methodName: String, signature: String, required: Boolean = false) {
      def filter(method: MethodNode) = method.name == methodName && method.desc == signature
      if (classNode.methods.exists(filter)) {
        if (required) {
          throw new InjectionFailedException(s"Could not inject method '$methodName$signature' because it was already present!")
        }
      }
      else template.methods.find(filter) match {
        case Some(method) => classNode.methods.add(method)
        case _ => throw new AssertionError()
      }
    }
    inject("node", "()Lli/cil/oc/api/network/Node;", required = true)
    inject("onConnect", "(Lli/cil/oc/api/network/Node;)V")
    inject("onDisconnect", "(Lli/cil/oc/api/network/Node;)V")
    inject("onMessage", "(Lli/cil/oc/api/network/Message;)V")

    log.trace("Injecting / wrapping overrides for required tile entity methods.")
    def replace(methodName: String, methodNameSrg: String, desc: String) {
      val mapper = FMLDeobfuscatingRemapper.INSTANCE
      def filter(method: MethodNode) = {
        val descDeObf = mapper.mapMethodDesc(method.desc)
        val methodNameDeObf = mapper.mapMethodName(tileEntityNameObfed, method.name, method.desc)
        val areSamePlain = method.name + descDeObf == methodName + desc
        val areSameDeObf = methodNameDeObf + descDeObf == methodNameSrg + desc
        areSamePlain || areSameDeObf
      }
      if (classNode.methods.exists(method => method.name == methodName + SimpleComponentImpl.PostFix && mapper.mapMethodDesc(method.desc) == desc)) {
        throw new InjectionFailedException(s"Delegator method name '${methodName + SimpleComponentImpl.PostFix}' is already in use.")
      }
      classNode.methods.find(filter) match {
        case Some(method) =>
          log.trace(s"Found original implementation of '$methodName', wrapping.")
          method.name = methodName + SimpleComponentImpl.PostFix
        case _ =>
          log.trace(s"No original implementation of '$methodName', will inject override.")
          def ensureNonFinalIn(name: String) {
            if (name != null) {
              val node = classNodeFor(name)
              if (node != null) {
                node.methods.find(filter) match {
                  case Some(method) =>
                    if ((method.access & Opcodes.ACC_FINAL) != 0) {
                      throw new InjectionFailedException(s"Method '$methodName' is final in superclass ${node.name.replace('/', '.')}.")
                    }
                  case _ =>
                }
                ensureNonFinalIn(node.superName)
              }
            }
          }
          ensureNonFinalIn(classNode.superName)
          template.methods.find(_.name == methodName + SimpleComponentImpl.PostFix) match {
            case Some(method) => classNode.methods.add(method)
            case _ => throw new AssertionError(s"Couldn't find '${methodName + SimpleComponentImpl.PostFix}' in template implementation.")
          }
      }
      template.methods.find(filter) match {
        case Some(method) => classNode.methods.add(method)
        case _ => throw new AssertionError(s"Couldn't find '$methodName' in template implementation.")
      }
    }
    replace("validate", "func_145829_t", "()V")
    replace("invalidate", "func_145843_s", "()V")
    replace("onChunkUnload", "func_76623_d", "()V")
    replace("readFromNBT", "func_145839_a", "(Lnet/minecraft/nbt/NBTTagCompound;)V")
    replace("writeToNBT", "func_145841_b", "(Lnet/minecraft/nbt/NBTTagCompound;)V")

    log.trace("Injecting interface.")
    classNode.interfaces.add("li/cil/oc/common/asm/template/SimpleComponentImpl")

    writeClass(classNode, ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES)
  }

  val tileEntityNamePlain = "net/minecraft/tileentity/TileEntity"
  val tileEntityNameObfed = FMLDeobfuscatingRemapper.INSTANCE.unmap(tileEntityNamePlain)

  def isTileEntity(classNode: ClassNode): Boolean = {
    if (classNode == null) false
    else {
      log.trace(s"Checking if class ${classNode.name} is a TileEntity...")
      classNode.name == tileEntityNamePlain || classNode.name == tileEntityNameObfed ||
        (classNode.superName != null && isTileEntity(classNodeFor(classNode.superName)))
    }
  }

  def classNodeFor(name: String) = {
    val namePlain = name.replace('/', '.')
    val bytes = loader.getClassBytes(namePlain)
    if (bytes != null) newClassNode(bytes)
    else {
      val nameObfed = FMLDeobfuscatingRemapper.INSTANCE.unmap(name).replace('/', '.')
      val bytes = loader.getClassBytes(nameObfed)
      if (bytes == null) null
      else newClassNode(bytes)
    }
  }

  def newClassNode(data: Array[Byte]) = {
    val classNode = new ClassNode()
    new ClassReader(data).accept(classNode, 0)
    classNode
  }

  def writeClass(classNode: ClassNode, flags: Int = ClassWriter.COMPUTE_MAXS) = {
    val writer = new ClassWriter(flags)
    classNode.accept(writer)
    writer.toByteArray
  }

  class InjectionFailedException(message: String) extends Exception(message)

}
