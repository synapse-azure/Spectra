/*
 * Copyright 2016 jagrosh.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package spectra;

import java.util.List;
import java.util.function.Function;
import net.dv8tion.jda.Permission;
import net.dv8tion.jda.entities.Role;
import net.dv8tion.jda.entities.TextChannel;
import net.dv8tion.jda.entities.User;
import net.dv8tion.jda.events.message.MessageReceivedEvent;
import net.dv8tion.jda.utils.PermissionUtil;
import spectra.tempdata.Cooldowns;
import spectra.utils.FinderUtil;
import spectra.utils.FormatUtil;

/**
 *
 * @author John Grosh (jagrosh)
 */
public abstract class Command {
    protected String command = "null";
    protected String[] aliases = new String[0];
    protected String help = "No help information given.";
    protected Argument[] arguments = new Argument[0];
    protected String longhelp = "There is no help information available for this command.\n"
            + "Please contact jagrosh if you see this";
    protected Command[] children = new Command[0];
    protected Permission[] requiredPermissions = new Permission[0];
    protected PermLevel level = PermLevel.EVERYONE;
    protected boolean availableInDM = true;
    protected int cooldown = 0; //seconds
    protected String separatorRegex = null;
    protected Function<MessageReceivedEvent,String> cooldownKey;
    
    protected abstract boolean execute(Object[] args, MessageReceivedEvent event);
    
    public boolean run(String args, MessageReceivedEvent event, PermLevel perm, boolean ignore, boolean banned)
    {
        return run(args, event, perm, ignore, banned, "");
    }
    
    public boolean run(String args, MessageReceivedEvent event, PermLevel perm, boolean ignore, boolean banned, String parentChain)
    {
        if("help".equalsIgnoreCase(args))//display help text if applicable
        {
            StringBuilder builder = new StringBuilder();
            builder.append("**Available help for `").append(parentChain).append(command).append("` ").append(event.isPrivate() ? "via Direct Message" : "in <#"+event.getTextChannel().getId()+">").append("**:\n");
            builder.append("Usage: `" + SpConst.PREFIX).append(parentChain).append(command).append("`").append(Argument.arrayToString(arguments));
            
            if(aliases.length>0)
            {
                builder.append("\nAliases:");
                for(String alias: aliases)
                    builder.append(" `").append(alias).append("`");
            }
            builder.append("\n*").append(longhelp).append("*\n");
            if(children.length>0)
            {
                builder.append("\n**Subcommands**:");
                PermLevel current = level;
                for(Command child: children)
                {
                    if(child.level!=current)
                    {
                        current = child.level;
                        switch(current)
                        {
                            case MODERATOR:
                                builder.append("\n**Moderator Commands**:");
                                break;
                            case ADMIN:
                                builder.append("\n**Admin Commands**:");
                                break;
                        }
                    }
                    builder.append("\n`" + SpConst.PREFIX).append(parentChain).append(command)
                            .append(" ").append(child.command).append("`").append(Argument.arrayToString(child.arguments))
                            .append(" - ").append(child.help);
                }
            }
            Sender.sendHelp(builder.toString(), event.getAuthor().getPrivateChannel(), event); 
            return true;
        }
        
        if(args!=null)//run child command if possible
        {
            String[] argv = FormatUtil.cleanSplit(args);
            for(Command child: children)
                if(child.isCommandFor(argv[0]))
                    return child.run(argv[1], event, perm, ignore, banned, parentChain+command+" ");
        }
        
        if(!availableInDM && event.isPrivate())//can't use in dm
        {
            Sender.sendPrivate(SpConst.NOT_VIA_DM, event.getPrivateChannel());
            return false;
        }
        if(!perm.isAtLeast(level))//not enough permission
            return false;
        if(ignore && (!perm.isAtLeast(PermLevel.ADMIN)))//ignore commands by non-admins
            return false;
        if(!event.isPrivate())
        {
            if(!PermissionUtil.checkPermission(event.getJDA().getSelfInfo(), Permission.MESSAGE_WRITE, event.getTextChannel()))
            {
                Sender.sendPrivate(String.format(SpConst.CANT_SEND, event.getTextChannel().getAsMention()), event.getAuthor().getPrivateChannel());
                return false;
            }
        }
        if(banned)
        {
            Sender.sendResponse(SpConst.BANNED_COMMAND + (perm.isAtLeast(PermLevel.ADMIN) ? String.format(SpConst.BANNED_COMMAND_IFADMIN, command, command) : ""), event);
            return false;
        }
        if(!event.isPrivate())
            for(Permission p : requiredPermissions)
            {
                if(!PermissionUtil.checkPermission(event.getJDA().getSelfInfo(), p, event.getTextChannel()))
                {
                    Sender.sendResponse(SpConst.NEED_PERMISSION+p ,event);
                    return false;
                }
            }
        
        String cdKey = null;
        if(cooldownKey!=null)// || perm==PermLevel.JAGROSH) //no cooldowns for jagrosh
            cdKey = cooldownKey.apply(event);
        long seconds = Cooldowns.getInstance().check(cdKey);
        if(seconds > 0)
        {
            Sender.sendResponse(String.format(SpConst.ON_COOLDOWN, FormatUtil.secondsToTime(seconds)), event);
            return false;
        }
        
        //parse arguments
        Object[] parsedArgs = new Object[arguments.length];
        String workingSet = args;
        for(int i=0; i<arguments.length; i++)
        {
            if(workingSet==null)
            {
                if (arguments[i].required)
                {
                    Sender.sendResponse(String.format(SpConst.TOO_FEW_ARGS,parentChain+command), event);
                    return false;
                }
                else continue;
            }
            switch(arguments[i].type)
            {
                case INTEGER:{
                    String[] parts = FormatUtil.cleanSplit(workingSet);
                    long num;
                    try{
                        num = Long.parseLong(parts[0]);
                        if(num < arguments[i].min || num > arguments[i].max)
                        {
                            Sender.sendResponse(String.format(SpConst.INVALID_INTEGER, arguments[i].name, arguments[i].min, arguments[i].max), event);
                            return false;
                        }
                    } catch(NumberFormatException e)
                    {
                        Sender.sendResponse(String.format(SpConst.INVALID_INTEGER, arguments[i].name, arguments[i].min, arguments[i].max), event);
                        return false;
                    }
                    parsedArgs[i] = num;
                    workingSet = parts[1];
                    break;}
                case SHORTSTRING:{
                    String[] parts = FormatUtil.cleanSplit(workingSet);
                    parsedArgs[i] = parts[0];
                    if(parts[0].length() < arguments[i].min || parts[0].length() > arguments[i].max)
                    {
                        Sender.sendResponse(String.format(SpConst.INVALID_LENGTH, arguments[i].name, arguments[i].min, arguments[i].max), event);
                        return false;
                    }
                    workingSet = parts[1];
                    break;}
                case LONGSTRING:{
                    String[] parts;
                    if(separatorRegex==null)
                        parts = new String[]{workingSet,null};
                    else
                        parts = FormatUtil.cleanSplit(workingSet, separatorRegex);
                    if(parts[0].length() < arguments[i].min || parts[0].length() > arguments[i].max)
                    {
                        Sender.sendResponse(String.format(SpConst.INVALID_LENGTH, arguments[i].name, arguments[i].min, arguments[i].max), event);
                        return false;
                    }
                    parsedArgs[i] = parts[0];
                    workingSet = parts[1];
                    break;}
                case TIME:{
                    String[] parts;
                    if(separatorRegex==null)
                        parts = new String[]{workingSet,null};
                    else
                        parts = FormatUtil.cleanSplit(workingSet, separatorRegex);
                    String timestr = parts[0].replaceAll("(?i)^((\\s*\\d+\\s*(d(ays?)?|h((ou)?rs?)?|m(in(ute)?s?)?|s(ec(ond)?s?)?)\\s?,?)*).*", "$1");
                    if(timestr.equals(""))
                    {
                        Sender.sendResponse(String.format(SpConst.INVALID_TIME, parts[0]), event);
                        return false;
                    }
                    if(timestr.length() < parts[0].length())
                    {
                        parts[1] = workingSet.substring(timestr.length()).trim();
                        if(parts[1].equals(""))
                            parts[1] = null;
                    }
                    
                    timestr = timestr.replaceAll("(?i)(\\d)([a-z])", "$1 $2").replaceAll("(?i)([a-z])(\\d)", "$1 $2").trim();
                    String[] vals = timestr.split("\\s+");
                    long timeinseconds = 0;
                    try{
                    for(int j=0; j<vals.length; j+=2)
                    {
                        long num = Long.parseLong(vals[j]);
                        if(vals[j+1].startsWith("m"))
                            num*=60;
                        else if(vals[j+1].startsWith("h"))
                            num*=60*60;
                        else if(vals[j+1].startsWith("d"))
                            num*=60*60*24;
                        timeinseconds+=num;
                    }
                    }catch(Exception e){
                        System.out.println("Failed parsing time:\ntimestr="+timestr);
                        Sender.sendResponse(String.format(SpConst.INVALID_TIME, parts[0]), event);
                        return false;
                    }
                    if(timeinseconds < arguments[i].min || timeinseconds > arguments[i].max)
                    {
                        Sender.sendResponse(String.format(SpConst.INVALID_TIME_RANGE, arguments[i].name, FormatUtil.secondsToTime(arguments[i].min), FormatUtil.secondsToTime(arguments[i].max)), event);
                        return false;
                    }
                    parsedArgs[i] = timeinseconds;
                    workingSet = parts[1];
                    break;}
                case USER:{
                    String[] parts;
                    if(separatorRegex==null)
                        parts = new String[]{workingSet,null};
                    else
                        parts = FormatUtil.cleanSplit(workingSet, separatorRegex);
                    List<User> ulist = null;
                    if(!event.isPrivate())
                        ulist = FinderUtil.findUsers(parts[0], event.getGuild());
                    if(ulist==null || ulist.isEmpty())
                        ulist = FinderUtil.findUsers(parts[0], event.getJDA());
                    if(ulist.isEmpty())
                    {
                        Sender.sendResponse(String.format(SpConst.NONE_FOUND, "users", parts[0]), event);
                        return false;
                    }
                    else if (ulist.size()>1)
                    {
                        Sender.sendResponse(FormatUtil.listOfUsers(ulist, parts[0]), event);
                        return false;
                    }
                    parsedArgs[i] = ulist.get(0);
                    workingSet = parts[1];
                    break;}
                case LOCALUSER:{
                    if(event.isPrivate())
                    {
                        Sender.sendResponse(String.format(SpConst.INVALID_IN_DM, arguments[i].name), event);
                        return false;
                    }
                    String[] parts;
                    if(separatorRegex==null)
                        parts = new String[]{workingSet,null};
                    else
                        parts = FormatUtil.cleanSplit(workingSet, separatorRegex);
                    List<User> ulist = FinderUtil.findUsers(parts[0], event.getGuild());
                    if(ulist.isEmpty())
                    {
                        Sender.sendResponse(String.format(SpConst.NONE_FOUND, "users", parts[0]), event);
                        return false;
                    }
                    else if (ulist.size()>1)
                    {
                        Sender.sendResponse(FormatUtil.listOfUsers(ulist, parts[0]), event);
                        return false;
                    }
                    parsedArgs[i] = ulist.get(0);
                    workingSet = parts[1];
                    break;}
                case TEXTCHANNEL:{
                    if(event.isPrivate())
                    {
                        Sender.sendResponse(String.format(SpConst.INVALID_IN_DM, arguments[i].name), event);
                        return false;
                    }
                    String[] parts = FormatUtil.cleanSplit(workingSet);
                    List<TextChannel> tclist = FinderUtil.findTextChannel(parts[0], event.getGuild());
                    if(tclist.isEmpty())
                    {
                        Sender.sendResponse(String.format(SpConst.NONE_FOUND, "text channels", parts[0]), event);
                        return false;
                    }
                    else if (tclist.size()>1)
                    {
                        Sender.sendResponse(FormatUtil.listOfChannels(tclist, parts[0]), event);
                        return false;
                    }
                    parsedArgs[i] = tclist.get(0);
                    workingSet = parts[1];
                    break;}
                case ROLE:{
                    if(event.isPrivate())
                    {
                        Sender.sendResponse(String.format(SpConst.INVALID_IN_DM, arguments[i].name), event);
                        return false;
                    }
                    String[] parts;
                    if(separatorRegex==null)
                        parts = new String[]{workingSet,null};
                    else
                        parts = FormatUtil.cleanSplit(workingSet,separatorRegex);
                    List<Role> rlist = FinderUtil.findRole(parts[0], event.getGuild().getRoles());
                    if(rlist.isEmpty())
                    {
                        Sender.sendResponse(String.format(SpConst.NONE_FOUND, "roles", parts[0]), event);
                        return false;
                    }
                    else if (rlist.size()>1)
                    {
                        Sender.sendResponse(FormatUtil.listOfRoles(rlist, parts[0]), event);
                        return false;
                    }
                    parsedArgs[i] = rlist.get(0);
                    workingSet = parts[1];
                    break;}
            }
        }
        
        
        seconds = Cooldowns.getInstance().checkAndApply(cdKey,cooldown);
        if(seconds > 0)
        {
            Sender.sendResponse(String.format(SpConst.ON_COOLDOWN, FormatUtil.secondsToTime(seconds)), event);
            return false;
        }
        
        boolean result = execute(parsedArgs,event);
        if(!result)
            Cooldowns.getInstance().resetCooldown(cdKey);
        return result;
    }

    public boolean isCommandFor(String string)
    {
        if(command.equalsIgnoreCase(string))
            return true;
        for(String alias : aliases)
            if(alias.equalsIgnoreCase(string))
                return true;
        return false;
    }

}
